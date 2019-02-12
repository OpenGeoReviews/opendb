package org.openplacereviews.opendb;

import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.prng.FixedSecureRandom;

public class SecUtils {
	public static final String SIG_ALGO_SHA1_EC = "SHA1withECDSA";
	public static final String SIG_ALGO_NONE_EC = "NonewithECDSA";
	public static final String ALGO_EC = "EC";
	public static final String EC_256SPEC_K1 = "secp256k1";
	
	public static final String KEYGEN_PWD_METHOD_1 = "EC256K1_S17R8";
	public static final String DECODE_BASE64 = "base64";
	public static final String HASH_SHA256_SALT = "sha256_salt";
	public static final String HASH_SHA256 = "sha256";
	public static final String HASH_SHA1 = "sha1";
	
	public static final String KEY_BASE64 = DECODE_BASE64;
	
	
	public static void main(String[] args) throws FailedVerificationException {
		KeyPair kp = generateECKeyPairFromPassword(KEYGEN_PWD_METHOD_1, "openplacereviews", "");
		System.out.println(kp.getPrivate().getFormat());
		System.out.println(kp.getPrivate().getAlgorithm());
		String pr = encodeKey(KEY_BASE64, kp.getPrivate());
		String pk = encodeKey(KEY_BASE64, kp.getPublic());
		String algo = kp.getPrivate().getAlgorithm();
		System.out.println(String.format("Private key: %s %s\nPublic key: %s %s", 
				kp.getPrivate().getFormat(), pr, kp.getPublic().getFormat(), pk));
		String signMessageTest = "Hello this is a registration message test";
		byte[] signature = signMessageWithKey(kp, signMessageTest.getBytes(), SIG_ALGO_SHA1_EC);
		System.out.println(String.format("Signed message: %s %s", Base64.getEncoder().encodeToString(signature), signMessageTest) );
		
		
		KeyPair nk = getKeyPair(algo, pr, pk);
		// validate
		pr = Base64.getEncoder().encodeToString(nk.getPrivate().getEncoded());
		pk = Base64.getEncoder().encodeToString(nk.getPublic().getEncoded());
		System.out.println(String.format("Private key: %s %s\nPublic key: %s %s", 
				nk.getPrivate().getFormat(), pr, nk.getPublic().getFormat(), pk));
		System.out.println(validateSignature(nk, signMessageTest.getBytes(), SIG_ALGO_SHA1_EC, signature));
		
	}
	
	public static EncodedKeySpec decodeKey(String key) {
		if(key.startsWith(KEY_BASE64 + ":")) {
			key = key.substring(KEY_BASE64.length() + 1);
			int s = key.indexOf(':');
			if (s == -1) {
				throw new IllegalArgumentException(String.format("Key doesn't contain algorithm of hashing to verify"));
			}
			return getKeySpecByFormat(key.substring(0, s), Base64.getDecoder().decode(key.substring(s + 1)));
		}
		throw new IllegalArgumentException(String.format("Key doesn't contain algorithm of hashing to verify"));
	}
	
	
	public static String encodeKey(String algo, PublicKey pk) {
		if(algo.equals(KEY_BASE64)) {
			return SecUtils.KEY_BASE64 + ":" + pk.getFormat() + ":" + encodeBase64(pk.getEncoded());
		}
		throw new UnsupportedOperationException("Algorithm is not supported: " + algo);
	}
	
	public static String encodeKey(String algo, PrivateKey pk) {
		if(algo.equals(KEY_BASE64)) {
			return SecUtils.KEY_BASE64 + ":" + pk.getFormat() + ":" + encodeBase64(pk.getEncoded());
		}
		throw new UnsupportedOperationException("Algorithm is not supported: " + algo);
	}
	
	public static EncodedKeySpec getKeySpecByFormat(String format, byte[] data) {
		switch(format) {
		case "PKCS#8": return new PKCS8EncodedKeySpec(data);
		case "X.509": return new X509EncodedKeySpec(data);
		}
		throw new IllegalArgumentException(format);
	}
	
	public static String encodeBase64(byte[] data) {
		return Base64.getEncoder().encodeToString(data);
	}
	
	public static boolean validateKeyPair(String algo, PrivateKey privateKey, PublicKey publicKey) throws FailedVerificationException {
		if(!algo.equals(ALGO_EC)) {
			throw new FailedVerificationException("Algorithm is not supported: " + algo);
		}
		// create a challenge
		byte[] challenge = new byte[512];
		ThreadLocalRandom.current().nextBytes(challenge);

		try {
			// sign using the private key
			Signature sig = Signature.getInstance(SIG_ALGO_SHA1_EC);
			sig.initSign(privateKey);
			sig.update(challenge);
			byte[] signature = sig.sign();

			// verify signature using the public key
			sig.initVerify(publicKey);
			sig.update(challenge);

			boolean keyPairMatches = sig.verify(signature);
			return keyPairMatches;
		} catch (InvalidKeyException e) {
			throw new FailedVerificationException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new FailedVerificationException(e);
		} catch (SignatureException e) {
			throw new FailedVerificationException(e);
		}
	}
	
	public static KeyPair getKeyPair(String algo, String prKey, String pbKey) throws FailedVerificationException {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(algo);
			PublicKey pb = null;
			PrivateKey pr = null;
			if (pbKey != null) {
				pb = keyFactory.generatePublic(decodeKey(pbKey));
			}
			if (prKey != null) {
				pr = keyFactory.generatePrivate(decodeKey(prKey));
			}
			return new KeyPair(pb, pr);
		} catch (NoSuchAlgorithmException e) {
			throw new FailedVerificationException(e);
		} catch (InvalidKeySpecException e) {
			throw new FailedVerificationException(e);
		}
	}

	
	public static KeyPair generateKeyPairFromPassword(String algo, String keygenMethod, String salt, String pwd) throws FailedVerificationException {
		if(algo.equals(ALGO_EC)) {
			return generateECKeyPairFromPassword(keygenMethod, salt, pwd);
		}
		throw new UnsupportedOperationException("Unsupported algo keygen method: " + algo);
	}
	
	public static KeyPair generateECKeyPairFromPassword(String keygenMethod, String salt, String pwd) throws FailedVerificationException {
		if(keygenMethod.equals(KEYGEN_PWD_METHOD_1)) {
			return generateEC256K1KeyPairFromPassword(salt, pwd);
		}
		throw new UnsupportedOperationException("Unsupported keygen method: " + keygenMethod);
	}
	
    // "EC:secp256k1:scrypt(salt,N:17,r:8,p:1,len:256)" algorithm - EC256K1_S17R8
	public static KeyPair generateEC256K1KeyPairFromPassword(String salt, String pwd) throws FailedVerificationException {
		try {
			KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGO_EC);
			ECGenParameterSpec ecSpec = new ECGenParameterSpec(EC_256SPEC_K1);
			if(pwd.length() < 10) {
				throw new IllegalArgumentException("Less than 10 characters produces only 50 bit entropy");
			}
			byte[] bytes = pwd.getBytes("UTF-8");
			byte[] scrypt = SCrypt.generate(bytes, salt.getBytes("UTF-8"), 1 << 17, 8, 1, 256);
			kpg.initialize(ecSpec, new FixedSecureRandom(scrypt));
			return kpg.genKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new FailedVerificationException(e);
		} catch (UnsupportedEncodingException e) {
			throw new FailedVerificationException(e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new FailedVerificationException(e);
		}
	}
	
	
	public static KeyPair generateRandomEC256K1KeyPair() throws FailedVerificationException {
		try {
			KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGO_EC);
			ECGenParameterSpec ecSpec = new ECGenParameterSpec(EC_256SPEC_K1);
			kpg.initialize(ecSpec);
			return kpg.genKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new FailedVerificationException(e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new FailedVerificationException(e);
		}
	}
	

	public static String signMessageWithKeyBase64(KeyPair keyPair, byte[] msg, String signAlgo) throws FailedVerificationException {
        String signature = Base64.getEncoder().encodeToString(signMessageWithKey(keyPair, msg, signAlgo));
        return DECODE_BASE64 + ":" +signature;
	}
	
	public static byte[] signMessageWithKey(KeyPair keyPair, byte[] msg, String signAlgo) throws FailedVerificationException {
		try {
			Signature sig = Signature.getInstance(signAlgo);
			sig.initSign(keyPair.getPrivate());
			sig.update(msg);
			byte[] signatureBytes = sig.sign();
			return signatureBytes;
		} catch (NoSuchAlgorithmException e) {
			throw new FailedVerificationException(e);
		} catch (InvalidKeyException e) {
			throw new FailedVerificationException(e);
		} catch (SignatureException e) {
			throw new FailedVerificationException(e);
		}
	}
	
	public static boolean validateSignature(KeyPair keyPair, byte[] msg, String hashAlgo, byte[] signature) throws FailedVerificationException {
		if(keyPair == null) {
			return false;
		}
		try {
			Signature sig = Signature.getInstance(hashAlgo);
			sig.initVerify(keyPair.getPublic());
			sig.update(msg);
			return sig.verify(signature);
		} catch (NoSuchAlgorithmException e) {
			throw new FailedVerificationException(e);
		} catch (InvalidKeyException e) {
			throw new FailedVerificationException(e);
		} catch (SignatureException e) {
			throw new FailedVerificationException(e);
		}
	}
	
	public static String calculateSha1(String msg) {
		 try {
			return DigestUtils.sha1Hex(msg.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}
	
	
	
	public static String calculateSha256(String msg) {
		 try {
			return DigestUtils.sha256Hex(msg.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}
	
	public static String calculateHash(String algo, String salt, String msg) {
		if(algo.equals(HASH_SHA256)) {
			return HASH_SHA256 + ":" + calculateSha256(msg);
		} else if(algo.equals(HASH_SHA1)) {
			return HASH_SHA1 + ":" + calculateSha1(msg);
		} else if(algo.equals(HASH_SHA256_SALT)) {
			return HASH_SHA256_SALT + ":" + calculateSha256(salt + msg);
		}
		throw new UnsupportedOperationException();
	}
	
	
	public static byte[] getHashBytes(String msg) {
		int i = msg.lastIndexOf(':');
		String s = i >= 0 ? msg.substring(i+1) : msg;
		try {
			return Hex.decodeHex(s);
		} catch (DecoderException e) {
			throw new IllegalArgumentException(e);
		}
	}
	
	public static boolean validateHash(String hash, String salt, String msg) {
		int s = hash.indexOf(":");
		if (s == -1) {
			throw new IllegalArgumentException(String.format("Hash %s doesn't contain algorithm of hashing to verify", s));
		}
		String v = calculateHash(hash.substring(0, s), salt, msg);
		return hash.equals(v);
	}


	public static byte[] decodeSignature(String digest) {
		try {
			if(digest.startsWith(DECODE_BASE64  + ":")) {
				return Base64.getDecoder().decode(digest.substring(DECODE_BASE64.length() + 1).getBytes("UTF-8"));
			}
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
		throw new IllegalArgumentException("Unknown format for signature " + digest);
	}



}
