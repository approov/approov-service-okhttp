package io.approov.util.sig;

public interface Signer {
    String getAlgorithm();
    byte[] sign(String base);
}
