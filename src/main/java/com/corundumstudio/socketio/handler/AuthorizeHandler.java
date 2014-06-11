/**
 * Copyright 2012 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio.handler;

import static io.netty.handler.codec.http.HttpHeaders.Names.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static io.netty.handler.codec.http.HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.ClientCookieEncoder;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.misc.HexDumpEncoder;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.Disconnectable;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.messages.AuthorizeMessage;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.namespace.NamespacesHub;
import com.corundumstudio.socketio.parser.Encoder;
import com.corundumstudio.socketio.parser.Packet;
import com.corundumstudio.socketio.parser.PacketType;
import com.corundumstudio.socketio.scheduler.CancelableScheduler;
import com.corundumstudio.socketio.scheduler.SchedulerKey;
import com.corundumstudio.socketio.scheduler.SchedulerKey.Type;
import com.corundumstudio.socketio.store.pubsub.ConnectMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubStore;
import com.corundumstudio.socketio.transport.MainBaseClient;
import com.google.common.io.BaseEncoding;

@Sharable
public class AuthorizeHandler extends ChannelInboundHandlerAdapter implements Disconnectable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final CancelableScheduler disconnectScheduler;
    private final Map<UUID, HandshakeData> authorizedSessionIds;

    private final String connectPath;
    private final Configuration configuration;
    private final NamespacesHub namespacesHub;
    private final Encoder encoder;

    public AuthorizeHandler(String connectPath, CancelableScheduler scheduler, Configuration configuration, NamespacesHub namespacesHub, Encoder encoder) {
        super();
        this.connectPath = connectPath;
        this.configuration = configuration;
        this.disconnectScheduler = scheduler;
        this.namespacesHub = namespacesHub;
        this.encoder = encoder;

        this.authorizedSessionIds = configuration.getStoreFactory().createMap("authorizedSessionIds");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            Channel channel = ctx.channel();
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.getUri());
            List<String> sid = queryDecoder.parameters().get("sid");

            if (!configuration.isAllowCustomRequests()
                    && !queryDecoder.path().startsWith(connectPath)) {
                HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
                channel.write(res).addListener(ChannelFutureListener.CLOSE);
                req.release();
                log.warn("Blocked wrong request! url: {}, ip: {}", queryDecoder.path(), channel.remoteAddress());
                return;
            }
            if (queryDecoder.path().equals(connectPath)
                    && sid == null) {
                String origin = req.headers().get(HttpHeaders.Names.ORIGIN);
                authorize(ctx, channel, origin, queryDecoder.parameters(), req);
                req.release();
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    private void authorize(ChannelHandlerContext ctx, Channel channel, String origin, Map<String, List<String>> params, FullHttpRequest req)
            throws IOException {
        Map<String, List<String>> headers = new HashMap<String, List<String>>(req.headers().names().size());
        for (String name : req.headers().names()) {
            List<String> values = req.headers().getAll(name);
            headers.put(name, values);
        }

        HandshakeData data = new HandshakeData(headers, params,
                                                (InetSocketAddress)channel.remoteAddress(),
                                                    req.getUri(), origin != null && !origin.equalsIgnoreCase("null"));

        boolean result = false;
        try {
            result = configuration.getAuthorizationListener().isAuthorized(data);
        } catch (Exception e) {
            log.error("Authorization error", e);
        }

        if (result) {
            UUID sessionId = UUID.randomUUID();

            scheduleDisconnect(channel, sessionId);

            Map map = new HashMap();
            map.put("sid", sessionId);
            map.put("upgrades", new String[] {});
//            map.put("upgrades", new String[] {"websocket"});
            map.put("pingInterval", configuration.getPollingDuration()*1000);
            map.put("pingTimeout", configuration.getCloseTimeout()*1000);

            channel.writeAndFlush(new AuthorizeMessage(map, null, origin, sessionId));

            authorizedSessionIds.put(sessionId, data);

//            String msg = createHandshake(sessionId);
//
//            List<String> jsonpParams = params.get("jsonp");
//            String jsonpParam = null;
//            if (jsonpParams != null) {
//                jsonpParam = jsonpParams.get(0);
//            }
//            channel.writeAndFlush(new AuthorizeMessage(msg, jsonpParam, origin, sessionId));
//
//            authorizedSessionIds.put(sessionId, data);
            log.debug("Handshake authorized for sessionId: {}", sessionId);
        } else {
            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            ChannelFuture f = channel.writeAndFlush(res);
            f.addListener(ChannelFutureListener.CLOSE);

            log.debug("Handshake unauthorized");
        }
    }

    public static byte[] longToBytes(long number) {
        int length = (int)(Math.log10(number)+1);
        byte[] res = new byte[length];
        int i = length;
        while (number > 0) {
            res[--i] = (byte) (number % 10);
            number = number / 10;
        }
        return res;
    }

    private String createHandshake(UUID sessionId) {
        String heartbeatTimeoutVal = String.valueOf(configuration.getHeartbeatTimeout());
        if (!configuration.isHeartbeatsEnabled()) {
            heartbeatTimeoutVal = "";
        }
        String msg = sessionId + ":" + heartbeatTimeoutVal + ":" + configuration.getCloseTimeout() + ":" + configuration.getTransports();
        return msg;
    }

    private void scheduleDisconnect(Channel channel, final UUID sessionId) {
        channel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                SchedulerKey key = new SchedulerKey(Type.AUTHORIZE, sessionId);
                disconnectScheduler.schedule(key, new Runnable() {
                    @Override
                    public void run() {
                        authorizedSessionIds.remove(sessionId);
                        log.debug("Authorized sessionId: {} removed due to connection timeout", sessionId);
                    }
                }, configuration.getCloseTimeout(), TimeUnit.SECONDS);
            }
        });
    }

    public HandshakeData getHandshakeData(UUID sessionId) {
        return authorizedSessionIds.get(sessionId);
    }

    public void connect(UUID sessionId) {
        SchedulerKey key = new SchedulerKey(Type.AUTHORIZE, sessionId);
        disconnectScheduler.cancel(key);
    }

    public void connect(MainBaseClient client) {
        connect(client.getSessionId());
        configuration.getStoreFactory().pubSubStore().publish(PubSubStore.CONNECT, new ConnectMessage(client.getSessionId()));

        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setData(PacketType.CONNECT.getValue());
        client.send(packet);

        Namespace ns = namespacesHub.get(Namespace.DEFAULT_NAME);
        SocketIOClient nsClient = client.addChildClient(ns);
        namespacesHub.get(ns.getName()).onConnect(nsClient);
    }

    @Override
    public void onDisconnect(MainBaseClient client) {
        authorizedSessionIds.remove(client.getSessionId());
    }

}
