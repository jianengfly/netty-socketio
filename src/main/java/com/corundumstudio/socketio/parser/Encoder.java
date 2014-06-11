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
package com.corundumstudio.socketio.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Queue;

import com.corundumstudio.socketio.Configuration;
import com.google.common.io.BaseEncoding;

public class Encoder {

    private final UTF8CharsScanner charsScanner = new UTF8CharsScanner();
    private final JsonSupport jsonSupport;
    private final Configuration configuration;

    public Encoder(Configuration configuration, JsonSupport jsonSupport) {
        this.jsonSupport = jsonSupport;
        this.configuration = configuration;
    }

    public void encodeJsonP(String param, String msg, ByteBuf out) throws IOException {
        String message = "io.j[" + param + "]("
                + jsonSupport.writeValueAsString(msg) + ");";
        out.writeBytes(message.getBytes(CharsetUtil.UTF_8));
    }

    public ByteBuf allocateBuffer(ByteBufAllocator allocator) {
        if (configuration.isPreferDirectBuffer()) {
            return allocator.ioBuffer();
        }

        return allocator.heapBuffer();
    }

    public void encodePackets(Queue<Packet> packets, ByteBuf buffer, ByteBufAllocator allocator) throws IOException {
        if (packets.size() == 1) {
            Packet packet = packets.poll();
            encodePacket(packet, buffer, allocator);
        } else {
            int counter = 0;
            while (true) {
                Packet packet = packets.poll();
                if (packet == null) {
                    break;
                }
                counter++;
                // to prevent infinity out message
                if (counter == 100) {
                    return;
                }

                ByteBuf packetBuffer = allocateBuffer(allocator);
                try {
                    int len = encodePacketWithLength(packet, packetBuffer, allocator);
                    byte[] lenBytes = toChars(len);

                    buffer.writeBytes(Packet.DELIMITER_BYTES);
                    buffer.writeBytes(lenBytes);
                    buffer.writeBytes(Packet.DELIMITER_BYTES);
                    buffer.writeBytes(packetBuffer);
                } finally {
                    packetBuffer.release();
                }
            }
        }
    }

    private byte toChar(int number) {
        return (byte) (number ^ 0x30);
    }

    final static char[] DigitTens = {'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '1', '1', '1', '1',
            '1', '1', '1', '1', '1', '1', '2', '2', '2', '2', '2', '2', '2', '2', '2', '2', '3', '3', '3',
            '3', '3', '3', '3', '3', '3', '3', '4', '4', '4', '4', '4', '4', '4', '4', '4', '4', '5', '5',
            '5', '5', '5', '5', '5', '5', '5', '5', '6', '6', '6', '6', '6', '6', '6', '6', '6', '6', '7',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',};

    final static char[] DigitOnes = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3',
            '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2',
            '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1',
            '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0',
            '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',};

    final static char[] digits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e',
            'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
            'y', 'z'};

    final static int[] sizeTable = {9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999,
            Integer.MAX_VALUE};

    // Requires positive x
    static int stringSize(long x) {
        for (int i = 0;; i++)
            if (x <= sizeTable[i])
                return i + 1;
    }

    static void getChars(long i, int index, byte[] buf) {
        long q, r;
        int charPos = index;
        byte sign = 0;

        if (i < 0) {
            sign = '-';
            i = -i;
        }

        // Generate two digits per iteration
        while (i >= 65536) {
            q = i / 100;
            // really: r = i - (q * 100);
            r = i - ((q << 6) + (q << 5) + (q << 2));
            i = q;
            buf[--charPos] = (byte) DigitOnes[(int)r];
            buf[--charPos] = (byte) DigitTens[(int)r];
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i <= 65536, i);
        for (;;) {
            q = (i * 52429) >>> (16 + 3);
            r = i - ((q << 3) + (q << 1)); // r = i-(q*10) ...
            buf[--charPos] = (byte) digits[(int)r];
            i = q;
            if (i == 0)
                break;
        }
        if (sign != 0) {
            buf[--charPos] = sign;
        }
    }

    public static byte[] toChars(long i) {
        int size = (i < 0) ? stringSize(-i) + 1 : stringSize(i);
        byte[] buf = new byte[size];
        getChars(i, size, buf);
        return buf;
    }

    public static byte[] longToBytes(long number) {
        // TODO optimize
        int length = (int)(Math.log10(number)+1);
        byte[] res = new byte[length];
        int i = length;
        while (number > 0) {
            res[--i] = (byte) (number % 10);
            number = number / 10;
        }
        return res;
    }

    public void encodePacket(Packet packet, ByteBuf buffer, ByteBufAllocator allocator) throws IOException {
        ByteBuf buf = allocateBuffer(allocator);
        buf.writeBytes(toChars(packet.getType().getValue()));

        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        switch (packet.getType()) {

        case EVENT:
            List<Object> args = packet.getArgs();
            if (args.isEmpty()) {
                args = null;
            }
            buffer.writeByte(Packet.SEPARATOR);
            Event event = new Event(packet.getName(), args);
            jsonSupport.writeValue(out, event);
            break;

        case OPEN:
            jsonSupport.writeValue(out, packet.getData());
            break;

        case MESSAGE:
            byte[] value = toChars((Integer)packet.getData());
            buf.writeBytes(value);
            break;

        case ACK:
            if (packet.getAckId() != null) {
                byte[] ackIdData = toChars(packet.getAckId());
                buf.writeBytes(ackIdData);
            }
//            if (!packet.getArgs().isEmpty()) {
//                buffer.writeByte('+');
//                jsonSupport.writeValue(out, packet.getArgs());
//            }
            break;

        case ERROR:
            if (packet.getReason() != null || packet.getAdvice() != null) {
                buffer.writeByte(Packet.SEPARATOR);
            }
            if (packet.getReason() != null) {
                int reasonCode = packet.getReason().getValue();
                buffer.writeByte(toChar(reasonCode));
            }
            if (packet.getAdvice() != null) {
                int adviceCode = packet.getAdvice().getValue();
                buffer.writeByte('+');
                buffer.writeByte(toChar(adviceCode));
            }
            break;
        }

        buffer.writeByte(0);
        int length = charsScanner.getLength(buf, 0);
        buffer.writeBytes(longToBytes(length));
        buffer.writeByte(0xff);
        buffer.writeBytes(buf);
    }

    private int encodePacketWithLength(Packet packet, ByteBuf buffer, ByteBufAllocator allocator) throws IOException {
        int start = buffer.writerIndex();
        encodePacket(packet, buffer, allocator);
        return charsScanner.getLength(buffer, start);
    }

}
