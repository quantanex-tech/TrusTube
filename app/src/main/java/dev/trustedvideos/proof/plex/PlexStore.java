// SPDX-License-Identifier: GPL-3.0-or-later
package dev.trustedvideos.proof.plex;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only a confirmed child session is saved. Parent account tokens stay in memory. */
public final class PlexStore {
    private static final String SIGN="trustube-plex-sign", ENCRYPT="trustube-plex-secrets";
    private final SharedPreferences prefs;
    public PlexStore(Context context) { prefs=context.getSharedPreferences("plex-connection-v1",Context.MODE_PRIVATE); }
    public boolean configured() { return prefs.contains("cipher"); }
    public SharedPreferences preferences() { return prefs; }
    public String clientId() { return prefs.getString("activeClient",jwtClientId()); }
    public String compatibilityClientId() {
        String id=prefs.getString("compatClient",null);
        if(id==null) { id=UUID.randomUUID().toString(); prefs.edit().putString("compatClient",id).commit(); }
        return id;
    }
    public String jwtClientId() {
        String id=prefs.getString("client",null);
        if(id==null) { id=UUID.randomUUID().toString(); prefs.edit().putString("client",id).commit(); }
        return id;
    }
    private KeyStore keys() throws Exception { KeyStore k=KeyStore.getInstance("AndroidKeyStore"); k.load(null); return k; }
    private static String b64(byte[] b) { return Base64.encodeToString(b,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING); }
    private static byte[] unsigned(BigInteger n) { byte[] b=n.toByteArray(); return b.length>1 && b[0]==0?java.util.Arrays.copyOfRange(b,1,b.length):b; }
    public JSONObject jwk() throws Exception {
        KeyStore k=keys();
        if(!k.containsAlias(SIGN)) {
            KeyPairGenerator g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(SIGN,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).setKeySize(2048).build());
            g.generateKeyPair(); k=keys();
        }
        RSAPublicKey p=(RSAPublicKey)k.getCertificate(SIGN).getPublicKey();
        return new JSONObject().put("kty","RSA").put("kid",jwtClientId()).put("alg","RS256").put("use","sig")
                .put("n",b64(unsigned(p.getModulus()))).put("e",b64(unsigned(p.getPublicExponent())));
    }
    public String deviceJwt() throws Exception {
        jwk(); long now=System.currentTimeMillis()/1000;
        String header=new JSONObject().put("alg","RS256").put("kid",jwtClientId()).put("typ","JWT").toString();
        String payload=new JSONObject().put("aud","plex.tv").put("iss",jwtClientId()).put("iat",now).put("exp",now+300).toString();
        String input=b64(header.getBytes(StandardCharsets.UTF_8))+"."+b64(payload.getBytes(StandardCharsets.UTF_8));
        Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign((java.security.PrivateKey)keys().getKey(SIGN,null));
        signer.update(input.getBytes(StandardCharsets.US_ASCII)); return input+"."+b64(signer.sign());
    }
    private SecretKey encryptionKey() throws Exception {
        KeyStore k=keys();
        if(!k.containsAlias(ENCRYPT)) {
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ENCRYPT,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); g.generateKey(); k=keys();
        }
        return (SecretKey)k.getKey(ENCRYPT,null);
    }
    public synchronized long nextGeneration() { return prefs.getLong("generation",0)+1; }
    public synchronized void save(PlexApi.Session s) throws Exception { save(s,clientId()); }
    public synchronized void save(PlexApi.Session s,String activeClient) throws Exception {
        JSONObject value=new JSONObject().put("childId",s.childId()).put("childName",s.childName())
                .put("account",s.childAccountToken()).put("serverId",s.serverId()).put("serverToken",s.serverToken())
                .put("origin",s.origin()).put("generation",s.generation());
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,encryptionKey());
        String cipher=b64(c.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)));
        if(!prefs.edit().putString("cipher",cipher).putString("iv",b64(c.getIV())).putLong("generation",s.generation()).putString("activeClient",activeClient).commit())
            throw new IOException("The child session could not be saved.");
    }
    public synchronized PlexApi.Session load() throws Exception {
        if(!prefs.contains("cipher")) return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,encryptionKey(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.URL_SAFE)));
        JSONObject s=new JSONObject(new String(c.doFinal(Base64.decode(prefs.getString("cipher",""),Base64.URL_SAFE)),StandardCharsets.UTF_8));
        return new PlexApi.Session(s.getString("childId"),s.getString("childName"),s.getString("account"),s.getString("serverId"),s.getString("serverToken"),s.getString("origin"),s.getLong("generation"));
    }
    public synchronized void disconnect() { prefs.edit().remove("cipher").remove("iv").putLong("generation",nextGeneration()).commit(); }
    public synchronized void checkConnection(PlexApi.Session s) throws IOException {
        if(!prefs.contains("cipher") || prefs.getLong("generation",-1)!=s.generation()) throw new IOException("The Plex connection changed.");
    }
    public synchronized void check(PlexApi.Session s,String episode) throws IOException {
        checkConnection(s);
        access.check(s,episode);
    }
    public interface Access { void check(PlexApi.Session session,String episode) throws IOException; }
    private volatile Access access = (session,episode) -> { throw new IOException("Plex access is not validated."); };
    public void access(Access value) { access=value; }
}
