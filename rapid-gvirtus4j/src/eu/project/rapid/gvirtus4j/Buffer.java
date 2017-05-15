package eu.project.rapid.gvirtus4j;

import java.io.IOException;

public final class Buffer {

    static {
        try {
            // FIXME: check for OS type before loading the lib
//            Util.loadNativLibFromResources(Buffer.class.getClassLoader(), "libs/libnative-lib.jnilib");
            Util.loadNativLibFromResources(Buffer.class.getClassLoader(), "libs/libnative-lib.so");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("JniTest - " + "UnsatisfiedLinkError, could not load native library: " + e);
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("JniTest - " + "IOException, could not load native library: " + e);
        }
    }

    private static String mpBuffer = "";

    public Buffer() {
        mpBuffer = "";
    }

    static void clear() {
        mpBuffer = "";
    }

    public static void AddPointerNull() {
        byte[] bites = {(byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0};
        mpBuffer += Util.bytesToHex(bites);
    }

    static void Add(int item) {
        byte[] bites = {(byte) item, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0};
        mpBuffer += Util.bytesToHex(bites);
    }

    static void Add(long item) {
        byte[] bites = {(byte) item, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0};
        mpBuffer += Util.bytesToHex(bites);
    }

    static void Add(String item) {
        byte[] bites = Util.hexToBytes(item);
        mpBuffer += Util.bytesToHex(bites);
    }

    static void Add(float[] item) {
        String js = prepareFloat(item);  // invoke the native method
        mpBuffer += js;

    }

    static void Add(int[] item) {
        Add(item.length * 4);
        for (int i = 0; i < item.length; i++) {
            AddInt(item[i]);
        }
    }

    static void AddInt(int item) {
        byte[] bits = Util.intToByteArray(item);
        mpBuffer += Util.bytesToHex(bits);

    }

    static void AddPointer(int item) {
        byte[] bites = {(byte) item, (byte) 0, (byte) 0, (byte) 0};
        int size = (Util.Sizeof.INT);
        Add(size);
        mpBuffer += Util.bytesToHex(bites);
    }

    static String GetString() {
        return mpBuffer;
    }

    static long Size() {
        return mpBuffer.length();
    }

    static void AddStruct(CudaDeviceProp struct) {
        byte[] bites = new byte[640];
        bites[0] = (byte) 0x78;
        bites[1] = (byte) 0x02;
        for (int i = 2; i < 640; i++) {
            bites[i] = (byte) 0;

        }
        mpBuffer += Util.bytesToHex(bites);
    }

    static void AddByte(int i) {
        String jps = prepareSingleByte(i);  // invoke the native method
        mpBuffer += jps;
    }

    static void AddByte4Ptx(String ptxSource, long size) {
        String jps = preparePtxSource(ptxSource, size);  // invoke the native method
        mpBuffer += jps;
    }

    public static void printMpBuffer() {
        System.out.println("mpBUFFER : " + mpBuffer);
    }

    public static native String prepareFloat(float[] floats);

    public static native String preparePtxSource(String ptxSource, long size);

    public static native String prepareSingleByte(int i);

}
