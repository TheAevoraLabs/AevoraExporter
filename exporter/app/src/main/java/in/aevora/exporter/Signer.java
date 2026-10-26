package in.aevora.exporter;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import javax.security.auth.x500.X500Principal;

/**
 * On-device APK signing with apksig + a per-device AndroidKeyStore key.
 *
 * No private key is embedded in the app. On first run, a RSA keypair is
 * generated in AndroidKeyStore with a self-signed certificate (Android
 * generates the cert from the KeyGenParameterSpec subject/dates). Every
 * APK this device exports is signed with that same key, so updates to an
 * exported APK from the same device install cleanly, and APKs from
 * another device do not collide on signature.
 *
 * Signing: v1 off, v2 + v3 on. v1 is not required for targetSdk 24+
 * installs on modern Android, and v2/v3 are what the installer checks.
 */
public class Signer {

    private static final String ALIAS = "aevora-exporter";

    public static void sign(File inputApk, File outputApk) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar notBefore = Calendar.getInstance();
            Calendar notAfter = Calendar.getInstance();
            notAfter.add(Calendar.YEAR, 25);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=Aevora Exporter, O=Aevora Labs"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(notBefore.getTime())
                    .setCertificateNotAfter(notAfter.getTime())
                    .build();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            kpg.initialize(spec);
            kpg.generateKeyPair();
        }
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        Certificate cert = ks.getCertificate(ALIAS);
        if (key == null || cert == null) {
            throw new IllegalStateException("AndroidKeyStore key/cert missing for " + ALIAS);
        }
        List<X509Certificate> chain = new ArrayList<>();
        chain.add((X509Certificate) cert);
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "aevora", key, chain).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(24)
                .build();
        signer.sign();
    }
}
