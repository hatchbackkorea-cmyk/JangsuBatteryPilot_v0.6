package com.seungjae.jangsu280battery;

interface IAvinoxFileService {
    void destroy() = 16777114;
    String[] listProtoFiles(String rootDir) = 1;
    byte[] readChunk(String path, long offset, int maxBytes) = 2;
}
