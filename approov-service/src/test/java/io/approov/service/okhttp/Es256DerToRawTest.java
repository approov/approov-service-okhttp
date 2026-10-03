package io.approov.service.okhttp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Random;

/**
 * Pins the conversion of the SDK's ASN.1 DER ES256 install signature into the
 * raw 64 byte r||s form RFC 9421 requires. Port of the differential harness in
 * approov/core-project-approov#806, shared with approov-service-android: golden
 * vectors produced by the previous BouncyCastle path (this layer's own
 * ASN1Integer path gave the same output for all of them), a seeded run of real
 * JDK P-256 signatures checked against the JDK's P1363 verifier, and the
 * malformed DER vectors.
 */
public class Es256DerToRawTest {
    private static final int DEFAULT_ITERATIONS = 20000;

    private static byte[] hex(String h) {
        h = h.replace(" ", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++)
            b.append(s);
        return b.toString();
    }

    private static byte[] convertOrNull(byte[] der) {
        try {
            return ApproovDefaultMessageSigning.es256DerToRaw(der);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Independent reference: r and s as unsigned 32 byte big-endian values. */
    private static byte[] referenceRaw(BigInteger r, BigInteger s) {
        byte[] raw = new byte[64];
        byte[] rb = r.toByteArray(), sb = s.toByteArray();
        int rl = Math.min(rb.length, 32), sl = Math.min(sb.length, 32);
        System.arraycopy(rb, rb.length - rl, raw, 32 - rl, rl);
        System.arraycopy(sb, sb.length - sl, raw, 64 - sl, sl);
        return raw;
    }

    /** Independent reference: minimal DER Ecdsa-Sig-Value for raw r||s. */
    private static byte[] referenceDer(byte[] raw) {
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    @Test
    public void goldenVectorsMatchPreviousBouncyCastleOutput() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("es256/der-to-raw-vectors.txt");
        assertNotNull("golden vector resource missing", in);
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split(" ");
                byte[] der = hex(parts[0]);
                byte[] expected = hex(parts[1]);
                assertArrayEquals("vector " + count + ": " + parts[0], expected, ApproovDefaultMessageSigning.es256DerToRaw(der));
                count++;
            }
        }
        // 64 per r/s length bucket where the seeded generator found that many, plus every
        // rarer short-integer case it found (see the counts in the resource header)
        assertEquals("golden vector count", 519, count);
    }

    @Test
    public void seededRealSignaturesConvertToVerifiableRawSignatures() throws Exception {
        int iterations = Integer.getInteger("approov.es256.iterations", DEFAULT_ITERATIONS);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = kpg.generateKeyPair();
        Signature derSigner = Signature.getInstance("SHA256withECDSA");
        Signature p1363Verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        Random random = new Random(1);
        int[] rLengths = new int[34];
        for (int i = 0; i < iterations; i++) {
            byte[] message = new byte[random.nextInt(200)];
            random.nextBytes(message);
            derSigner.initSign(keyPair.getPrivate());
            derSigner.update(message);
            byte[] der = derSigner.sign();
            rLengths[der[3] & 0xff]++;

            byte[] raw = ApproovDefaultMessageSigning.es256DerToRaw(der);
            assertEquals(64, raw.length);
            p1363Verifier.initVerify(keyPair.getPublic());
            p1363Verifier.update(message);
            assertTrue("raw signature " + i + " must verify as P1363", p1363Verifier.verify(raw));
            assertArrayEquals("raw signature " + i + " must round-trip to the SDK's DER", der, referenceDer(raw));
        }
        if (iterations >= DEFAULT_ITERATIONS) {
            // the seeded run must exercise padded, full and short r encodings
            assertTrue(rLengths[31] > 0);
            assertTrue(rLengths[32] > 0);
            assertTrue(rLengths[33] > 0);
        }
    }

    private static final String R32 = "11" + rep("22", 31);
    private static final String S32 = "33" + rep("44", 31);
    private static final String R33 = "00" + "ff" + rep("ee", 31);
    private static final String R31 = "7f" + rep("aa", 30);

    @Test
    public void wellFormedEdgeVectorsArePaddedToThirtyTwoBytes() {
        BigInteger r32 = new BigInteger(R32, 16), s32 = new BigInteger(S32, 16);
        BigInteger r33 = new BigInteger(R33, 16), r31 = new BigInteger(R31, 16);
        Object[][] vectors = {
                {"32/32", "3044 0220" + R32 + " 0220" + S32, referenceRaw(r32, s32)},
                {"33/32 (sign pad)", "3045 0221" + R33 + " 0220" + S32, referenceRaw(r33, s32)},
                {"33/33", "3046 0221" + R33 + " 0221" + R33, referenceRaw(r33, r33)},
                {"31/32 (left pad)", "3043 021f" + R31 + " 0220" + S32, referenceRaw(r31, s32)},
                {"1/1 tiny", "3006 020101 020102", referenceRaw(BigInteger.ONE, BigInteger.valueOf(2))},
                {"zero r", "3006 020100 020101", referenceRaw(BigInteger.ZERO, BigInteger.ONE)},
        };
        for (Object[] v : vectors) {
            assertArrayEquals((String) v[0], (byte[]) v[2], ApproovDefaultMessageSigning.es256DerToRaw(hex((String) v[1])));
        }
    }

    @Test
    public void malformedDerVectors() {
        // {name, der hex, rejected}. BouncyCastle accepted the non-minimal length,
        // BER indefinite length, trailing byte, third integer and negative r cases;
        // the SDK never produces them and strict DER rejects them (signing then
        // proceeds unsigned with a log, as for any undecodable signature).
        Object[][] vectors = {
                {"long-form seq len 0x81 (non-minimal)", "308144 0220" + R32 + " 0220" + S32, true},
                {"BER indefinite length", "3080 0220" + R32 + " 0220" + S32 + " 0000", true},
                {"trailing byte after seq", "3044 0220" + R32 + " 0220" + S32 + " 00", true},
                {"3 integers in seq", "3047 0220" + R32 + " 0220" + S32 + " 020101", true},
                {"wrong outer tag 0x31", "3144 0220" + R32 + " 0220" + S32, true},
                {"wrong int tag 0x03", "3044 0320" + R32 + " 0220" + S32, true},
                {"negative r minimal (0x8f..)", "3044 0220" + "8f" + rep("ee", 31) + " 0220" + S32, true},
                {"negative r (high bit, no pad)", "3044 0220" + "ff" + rep("ee", 31) + " 0220" + S32, true},
                {"non-minimal r (00 00 pad)", "3046 0222 00" + R33 + " 0220" + S32, true},
                {"redundant 00 before small r", "3044 0220 00" + R31 + " 0220" + S32, true},
                {"r 33 bytes no 00 lead", "3045 0221 01" + R32 + " 0220" + S32, true},
                {"r 34 bytes", "3046 0222 0000" + R32 + " 0220" + S32, true},
                // valid DER integers too large for P-256: Tink's ecdsaDer2Ieee alone throws
                // ArrayIndexOutOfBounds for r and silently corrupts r for s
                {"s 33 bytes no 00 lead", "3045 0220" + R32 + " 0221 01" + S32, true},
                {"r 34 bytes minimal", "3046 0222 0101" + R32 + " 0220" + S32, true},
                {"s 34 bytes minimal", "3046 0220" + R32 + " 0222 0101" + S32, true},
                {"s 33 bytes with 00 lead but no high bit", "3045 0220" + R32 + " 0221 00" + S32, true},
                {"truncated", "3044 0220" + R32 + " 0220" + S32.substring(0, 40), true},
                {"empty", "", true},
        };
        StringBuilder mismatches = new StringBuilder();
        for (Object[] v : vectors) {
            boolean rejected = convertOrNull(hex((String) v[1])) == null;
            if (rejected != (Boolean) v[2])
                mismatches.append(v[0]).append(rejected ? " rejected" : " accepted").append("; ");
        }
        if (mismatches.length() > 0)
            fail("unexpected malformed DER handling: " + mismatches);
    }
}
