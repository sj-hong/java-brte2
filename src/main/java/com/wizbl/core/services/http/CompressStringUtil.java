package com.wizbl.core.services.http;

import java.io.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class CompressStringUtil {
    private static final String charsetName = "UTF-8";

    /**
     * String 을 압축합니다.
     * @param string
     * @return
     */
    public synchronized static String compressString(String string) {
        return byteToString(compress(string));
    }

    /**
     * 압축된 String을 압축을 풉니다.
     * @param compressed
     * @return
     */
    public synchronized static String decompressString(String compressed) {
        return decompress(hexToByteArray(compressed));
    }

    private static String byteToString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        try {
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return sb.toString();
    }

    private static byte[] compress(String text) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            OutputStream out = new DeflaterOutputStream(baos);
            out.write(text.getBytes(charsetName));
            out.close();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return baos.toByteArray();
    }


    private static String decompress(byte[] bytes) {
        InputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0)
                baos.write(buffer, 0, len);
            return new String(baos.toByteArray(), charsetName);
        } catch (IOException e) {
            e.printStackTrace();
            throw new AssertionError(e);
        }
    }

    /**
     * 16진수 문자열을 byte 배열로 변환한다.
     */
    private static byte[] hexToByteArray(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[]{};
        }

        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            byte value = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            bytes[(int) Math.floor(i / 2)] = value;
        }
        return bytes;
    }

}