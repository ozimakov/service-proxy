/* Copyright 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core.transport.ssl;

import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import com.predic8.membrane.core.config.security.SSLParser;
import com.predic8.membrane.core.config.security.Store;
import com.predic8.membrane.core.resolver.ResolverMap;
import com.predic8.membrane.core.transport.TrustManagerWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.crypto.Cipher;
import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

public class SSLContext implements SSLProvider {

	private static final String DEFAULT_CERTIFICATE_SHA256 = "c7:e3:fd:97:2f:d3:b9:4f:38:87:9c:45:32:70:b3:d8:c1:9f:d1:64:39:fc:48:5f:f4:a1:6a:95:b5:ca:08:f7";
	private static boolean default_certificate_warned = false;
	private static boolean limitedStrength;

	private static final Log log = LogFactory.getLog(SSLContext.class.getName());

	private static Method setUseCipherSuitesOrderMethod, getSSLParametersMethod, setSSLParametersMethod;

	static {
		String dhKeySize = System.getProperty("jdk.tls.ephemeralDHKeySize");
		if (dhKeySize == null || "legacy".equals(dhKeySize))
			System.setProperty("jdk.tls.ephemeralDHKeySize", "matched");

		try {
			limitedStrength = Cipher.getMaxAllowedKeyLength("AES") <= 128;
			if (limitedStrength)
				log.warn("Your Java Virtual Machine does not have unlimited strength cryptography. If it is legal in your country, we strongly advise installing the Java Cryptography Extension (JCE) Unlimited Strength Jurisdiction Policy Files.");
		} catch (NoSuchAlgorithmException ignored) {
		}
		try {
			getSSLParametersMethod = SSLServerSocket.class.getMethod("getSSLParameters", new Class[] {});
			setSSLParametersMethod = SSLServerSocket.class.getMethod("setSSLParameters", new Class[] { SSLParameters.class });
			setUseCipherSuitesOrderMethod = SSLParameters.class.getMethod("setUseCipherSuitesOrder", new Class[] { Boolean.TYPE });
		} catch (SecurityException e) {
			throw new RuntimeException(e);
		} catch (NoSuchMethodException e) {
			// do nothing
		}
	}


	private final SSLParser sslParser;
	private List<String> dnsNames;

	private final javax.net.ssl.SSLContext sslc;
	private final String[] ciphers;
	private final String[] protocols;
	private final boolean wantClientAuth, needClientAuth;


	public SSLContext(SSLParser sslParser, ResolverMap resourceResolver, String baseLocation) {
		this.sslParser = sslParser;
		try {
			String algorihm = KeyManagerFactory.getDefaultAlgorithm();
			if (sslParser.getAlgorithm() != null)
				algorihm = sslParser.getAlgorithm();

			KeyManagerFactory kmf = null;
			String keyStoreType = "JKS";
			if (sslParser.getKeyStore() != null) {
				if (sslParser.getKeyStore().getKeyAlias() != null)
					throw new InvalidParameterException("keyAlias is not yet supported.");
				char[] keyPass = "changeit".toCharArray();
				if (sslParser.getKeyStore().getKeyPassword() != null)
					keyPass = sslParser.getKeyStore().getKeyPassword().toCharArray();

				if (sslParser.getKeyStore().getType() != null)
					keyStoreType = sslParser.getKeyStore().getType();
				KeyStore ks = openKeyStore(sslParser.getKeyStore(), "JKS", keyPass, resourceResolver, baseLocation);
				kmf = KeyManagerFactory.getInstance(algorihm);
				kmf.init(ks, keyPass);

				Enumeration<String> aliases = ks.aliases();
				while (aliases.hasMoreElements()) {
					String alias = aliases.nextElement();
					if (ks.isKeyEntry(alias)) {
						// first key is used by the KeyManagerFactory
						dnsNames = getDNSNames(ks.getCertificate(alias));
						break;
					}
				}
			}
			if (sslParser.getKey() != null) {
				if (kmf != null)
					throw new InvalidParameterException("<trust> may not be used together with <truststore>.");

				KeyStore ks = KeyStore.getInstance(keyStoreType);
				ks.load(null, "".toCharArray());

				List<Certificate> certs = new ArrayList<Certificate>();

				for (com.predic8.membrane.core.config.security.Certificate cert : sslParser.getKey().getCertificates())
					certs.add(PEMSupport.getInstance().parseCertificate(cert.get(resourceResolver, baseLocation)));
				if (certs.size() == 0)
					throw new RuntimeException("At least one //ssl/key/certificate is required.");
				dnsNames = getDNSNames(certs.get(0));

				checkChainValidity(certs);
				Object key = PEMSupport.getInstance().parseKey(sslParser.getKey().getPrivate().get(resourceResolver, baseLocation));
				Key k = key instanceof Key ? (Key) key : ((KeyPair)key).getPrivate();
				if (k instanceof RSAPrivateCrtKey && certs.get(0).getPublicKey() instanceof RSAPublicKey) {
					RSAPrivateCrtKey privkey = (RSAPrivateCrtKey)k;
					RSAPublicKey pubkey = (RSAPublicKey) certs.get(0).getPublicKey();
					if (!(privkey.getModulus().equals(pubkey.getModulus()) && privkey.getPublicExponent().equals(pubkey.getPublicExponent())))
						log.warn("Certificate does not fit to key.");
				}

				ks.setKeyEntry("inlinePemKeyAndCertificate", k, "".toCharArray(),  certs.toArray(new Certificate[certs.size()]));

				kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				String keyPassword = "";
				if (sslParser.getKey().getPassword() != null)
					keyPassword = sslParser.getKey().getPassword();
				kmf.init(ks, keyPassword.toCharArray());
			}

			TrustManagerFactory tmf = null;
			if (sslParser.getTrustStore() != null) {
				String trustAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
				if (sslParser.getTrustStore().getAlgorithm() != null)
					trustAlgorithm = sslParser.getTrustStore().getAlgorithm();
				KeyStore ks = openKeyStore(sslParser.getTrustStore(), keyStoreType, null, resourceResolver, baseLocation);
				tmf = TrustManagerFactory.getInstance(trustAlgorithm);
				tmf.init(ks);
			}
			if (sslParser.getTrust() != null) {
				if (tmf != null)
					throw new InvalidParameterException("<trust> may not be used together with <truststore>.");

				KeyStore ks = KeyStore.getInstance(keyStoreType);
				ks.load(null, "".toCharArray());

				for (int j = 0; j < sslParser.getTrust().getCertificateList().size(); j++)
					ks.setCertificateEntry("inlinePemCertificate" + j, PEMSupport.getInstance().parseCertificate(sslParser.getTrust().getCertificateList().get(j).get(resourceResolver, baseLocation)));

				tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(ks);
			}

			TrustManager[] tms = tmf != null ? tmf.getTrustManagers() : null /* trust anyone: new TrustManager[] { new NullTrustManager() } */;
			if (sslParser.isIgnoreTimestampCheckFailure())
				tms = new TrustManager[] { new TrustManagerWrapper(tms, true) };

			if (sslParser.getProtocol() != null)
				sslc = javax.net.ssl.SSLContext.getInstance(sslParser.getProtocol());
			else
				sslc = javax.net.ssl.SSLContext.getInstance("TLS");

			sslc.init(
					kmf != null ? kmf.getKeyManagers() : null,
							tms,
							null);

			if (sslParser.getCiphers() != null) {
				ciphers = sslParser.getCiphers().split(",");
				Set<String> supportedCiphers = Sets.newHashSet(sslc.getSocketFactory().getSupportedCipherSuites());
				for (String cipher : ciphers) {
					if (!supportedCiphers.contains(cipher))
						throw new InvalidParameterException("Unknown cipher " + cipher);
					if (cipher.contains("_RC4_"))
						log.warn("Cipher " + cipher + " uses RC4, which is deprecated.");
				}
			} else {
				// use all default ciphers except those using RC4
				String supportedCiphers[] = sslc.getSocketFactory().getDefaultCipherSuites();
				ArrayList<String> ciphers = new ArrayList<String>(supportedCiphers.length);
				for (String cipher : supportedCiphers)
					if (!cipher.contains("_RC4_"))
						ciphers.add(cipher);
				sortCiphers(ciphers);
				this.ciphers = ciphers.toArray(new String[ciphers.size()]);
			}
			if (setUseCipherSuitesOrderMethod == null)
				log.warn("Cannot set the cipher suite order before Java 8. This prevents Forward Secrecy with some SSL clients.");

			if (sslParser.getProtocols() != null) {
				protocols = sslParser.getProtocols().split(",");
			} else {
				protocols = null;
			}

			if (sslParser.getClientAuth() == null) {
				needClientAuth = false;
				wantClientAuth = false;
			} else if (sslParser.getClientAuth().equals("need")) {
				needClientAuth = true;
				wantClientAuth = true;
			} else if (sslParser.getClientAuth().equals("want")) {
				needClientAuth = false;
				wantClientAuth = true;
			} else {
				throw new RuntimeException("Invalid value '"+sslParser.getClientAuth()+"' in clientAuth: expected 'want', 'need' or not set.");
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void checkChainValidity(List<Certificate> certs) {
		boolean valid = true;
		for (int i = 0; i < certs.size() - 1; i++) {
			String currentIssuer = ((X509Certificate)certs.get(i)).getIssuerX500Principal().toString();
			String nextSubject = ((X509Certificate)certs.get(i+1)).getSubjectX500Principal().toString();
			valid = valid && Objects.equal(currentIssuer, nextSubject);
		}
		if (!valid) {
			StringBuilder sb = new StringBuilder();
			sb.append("Certificate chain is not valid:\n");
			for (int i = 0; i < certs.size(); i++) {
				sb.append("Cert " + String.format("%2d", i) + ": Subject: " + ((X509Certificate)certs.get(i)).getSubjectX500Principal().toString() + "\n");
				sb.append("         Issuer: " + ((X509Certificate)certs.get(i)).getIssuerX500Principal().toString() + "\n");
			}
			log.warn(sb.toString());
		}
	}

	private List<String> getDNSNames(Certificate certificate) throws CertificateParsingException {
		ArrayList<String> dnsNames = new ArrayList<String>();
		if (certificate instanceof X509Certificate) {
			X509Certificate x = (X509Certificate) certificate;

			Collection<List<?>> subjectAlternativeNames = x.getSubjectAlternativeNames();
			if (subjectAlternativeNames != null)
				for (List<?> l : subjectAlternativeNames) {
					if (l.get(0) instanceof Integer && ((Integer)l.get(0) == 2))
						dnsNames.add(l.get(1).toString());
				}
		}
		return dnsNames;
	}

	private static class CipherInfo {
		public final String cipher;
		public final int points;

		public CipherInfo(String cipher) {
			this.cipher = cipher;
			int points = 0;
			if (supportsPFS(cipher))
				points = 1;

			this.points = points;
		}

		private boolean supportsPFS(String cipher2) {
			// see https://en.wikipedia.org/wiki/Forward_secrecy#Protocols
			return cipher.contains("_DHE_RSA_") || cipher.contains("_DHE_DSS_") || cipher.contains("_ECDHE_RSA_") || cipher.contains("_ECDHE_ECDSA_");
		}
	}

	private void sortCiphers(ArrayList<String> ciphers) {
		ArrayList<CipherInfo> cipherInfos = new ArrayList<SSLContext.CipherInfo>(ciphers.size());

		for (String cipher : ciphers)
			cipherInfos.add(new CipherInfo(cipher));

		Collections.sort(cipherInfos, new Comparator<CipherInfo>() {
			@Override
			public int compare(CipherInfo cipher1, CipherInfo cipher2) {
				return cipher2.points - cipher1.points;
			}
		});

		for (int i = 0; i < ciphers.size(); i++)
			ciphers.set(i, cipherInfos.get(i).cipher);
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof SSLContext))
			return false;
		SSLContext other = (SSLContext)obj;
		return Objects.equal(sslParser, other.sslParser);
	}

	private KeyStore openKeyStore(Store store, String defaultType, char[] keyPass, ResolverMap resourceResolver, String baseLocation) throws NoSuchAlgorithmException, CertificateException, FileNotFoundException, IOException, KeyStoreException, NoSuchProviderException {
		String type = store.getType();
		if (type == null)
			type = defaultType;
		char[] password = keyPass;
		if (store.getPassword() != null)
			password = store.getPassword().toCharArray();
		if (password == null)
			throw new InvalidParameterException("Password for key store is not set.");
		KeyStore ks;
		if (store.getProvider() != null)
			ks = KeyStore.getInstance(type, store.getProvider());
		else
			ks = KeyStore.getInstance(type);
		ks.load(resourceResolver.resolve(ResolverMap.combine(baseLocation, store.getLocation())), password);
		if (!default_certificate_warned && ks.getCertificate("membrane") != null) {
			byte[] pkeEnc = ks.getCertificate("membrane").getEncoded();
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(pkeEnc);
			byte[] mdbytes = md.digest();
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < mdbytes.length; i++) {
				if (i > 0)
					sb.append(':');
				sb.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
			}
			if (sb.toString().equals(DEFAULT_CERTIFICATE_SHA256)) {
				log.warn("Using Membrane with the default certificate. This is highly discouraged! "
						+ "Please run the generate-ssl-keys script in the conf directory.");
				default_certificate_warned = true;
			}
		}
		return ks;
	}

	public void applyCiphers(SSLSocket sslSocket) {
		if (ciphers != null) {
			SSLParameters sslParameters = sslSocket.getSSLParameters();
			applyCipherOrdering(sslParameters);
			sslParameters.setCipherSuites(ciphers);
			sslParameters.setEndpointIdentificationAlgorithm(sslParser.getEndpointIdentificationAlgorithm());
			sslSocket.setSSLParameters(sslParameters);
		}
	}

	public void applyCiphers(SSLServerSocket sslServerSocket) {
		if (ciphers != null) {
			if (getSSLParametersMethod == null || setSSLParametersMethod == null) {
				sslServerSocket.setEnabledCipherSuites(ciphers);
			} else {
				SSLParameters sslParameters;
				try {
					// "sslParameters = sslServerSocket.getSSLParameters();" works only on Java 7+
					sslParameters = (SSLParameters) getSSLParametersMethod.invoke(sslServerSocket, new Object[] {});
					applyCipherOrdering(sslParameters);
					sslParameters.setCipherSuites(ciphers);
					// "sslServerSocket.setSSLParameters(sslParameters);" works only on Java 7+
					setSSLParametersMethod.invoke(sslServerSocket, new Object[] { sslParameters });
				} catch (IllegalAccessException e) {
					throw new RuntimeException(e);
				} catch (InvocationTargetException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private void applyCipherOrdering(SSLParameters sslParameters) {
		// "sslParameters.setUseCipherSuitesOrder(true);" works only on Java 8
		try {
			if (setUseCipherSuitesOrderMethod != null)
				setUseCipherSuitesOrderMethod.invoke(sslParameters, true);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}

	public ServerSocket createServerSocket(int port, int backlog, InetAddress bindAddress) throws IOException {
		SSLServerSocketFactory sslssf = sslc.getServerSocketFactory();
		SSLServerSocket sslss = (SSLServerSocket) sslssf.createServerSocket(port, backlog, bindAddress);
		applyCiphers(sslss);
		if (protocols != null) {
			sslss.setEnabledProtocols(protocols);
		} else {
			String[] protocols = sslss.getEnabledProtocols();
			Set<String> set = new HashSet<String>();
			for (String protocol : protocols) {
				if (protocol.equals("SSLv3") || protocol.equals("SSLv2Hello")) {
					continue;
				}
				set.add(protocol);
			}
			sslss.setEnabledProtocols(set.toArray(new String[0]));
		}
		sslss.setWantClientAuth(wantClientAuth);
		sslss.setNeedClientAuth(needClientAuth);
		return sslss;
	}

	public Socket wrapAcceptedSocket(Socket socket) throws IOException {
		return socket;
	}

	public Socket createSocket(String host, int port, int connectTimeout) throws IOException {
		Socket s = new Socket();
		s.connect(new InetSocketAddress(host, port), connectTimeout);
		SSLSocketFactory sslsf = sslc.getSocketFactory();
		SSLSocket ssls = (SSLSocket) sslsf.createSocket(s, host, port, true);
		if (protocols != null) {
			ssls.setEnabledProtocols(protocols);
		} else {
			String[] protocols = ssls.getEnabledProtocols();
			Set<String> set = new HashSet<String>();
			for (String protocol : protocols) {
				if (protocol.equals("SSLv3") || protocol.equals("SSLv2Hello")) {
					continue;
				}
				set.add(protocol);
			}
			ssls.setEnabledProtocols(set.toArray(new String[0]));
		}
		applyCiphers(ssls);
		return ssls;
	}

	public Socket createSocket(String host, int port, InetAddress addr, int localPort, int connectTimeout) throws IOException {
		Socket s = new Socket();
		s.bind(new InetSocketAddress(addr, localPort));
		s.connect(new InetSocketAddress(host, port), connectTimeout);
		SSLSocketFactory sslsf = sslc.getSocketFactory();
		SSLSocket ssls = (SSLSocket) sslsf.createSocket(s, host, port, true);
		applyCiphers(ssls);
		if (protocols != null) {
			ssls.setEnabledProtocols(protocols);
		} else {
			String[] protocols = ssls.getEnabledProtocols();
			Set<String> set = new HashSet<String>();
			for (String protocol : protocols) {
				if (protocol.equals("SSLv3") || protocol.equals("SSLv2Hello")) {
					continue;
				}
				set.add(protocol);
			}
			ssls.setEnabledProtocols(set.toArray(new String[0]));
		}
		return ssls;
	}

	SSLSocketFactory getSocketFactory() {
		return sslc.getSocketFactory();
	}

	String[] getCiphers() {
		return ciphers;
	}

	String[] getProtocols() {
		return protocols;
	}

	boolean isNeedClientAuth() {
		return needClientAuth;
	}

	boolean isWantClientAuth() {
		return wantClientAuth;
	}

	List<String> getDnsNames() {
		return dnsNames;
	}

	/**
	 *
	 * @return Human-readable description of there the keystore lives.
	 */
	String getLocation() {
		return sslParser.getKeyStore() != null ? sslParser.getKeyStore().getLocation() : "null";
	}

	public Socket wrap(Socket socket, byte[] buffer, int position) throws IOException {
		SSLSocketFactory serviceSocketFac = getSocketFactory();

		ByteArrayInputStream bais = new ByteArrayInputStream(buffer, 0, position);

		SSLSocket serviceSocket;
		// "serviceSocket = (SSLSocket)serviceSocketFac.createSocket(socket, bais, true);" only compileable with Java 1.8
		try {
			serviceSocket = (SSLSocket) SSLContextCollection.createSocketMethod.invoke(serviceSocketFac, new Object[] { socket, bais, true });
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			throw new RuntimeException(e);
		}

		applyCiphers(serviceSocket);
		if (getProtocols() != null) {
			serviceSocket.setEnabledProtocols(getProtocols());
		} else {
			String[] protocols = serviceSocket.getEnabledProtocols();
			Set<String> set = new HashSet<String>();
			for (String protocol : protocols) {
				if (protocol.equals("SSLv3") || protocol.equals("SSLv2Hello")) {
					continue;
				}
				set.add(protocol);
			}
			serviceSocket.setEnabledProtocols(set.toArray(new String[0]));
		}
		serviceSocket.setWantClientAuth(isWantClientAuth());
		serviceSocket.setNeedClientAuth(isNeedClientAuth());
		return serviceSocket;
	}
}
