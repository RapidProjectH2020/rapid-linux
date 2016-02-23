package eu.project.rapid.common;

import static eu.project.rapid.common.Constants.AC_RM_PORT_DEFAULT;
import static eu.project.rapid.common.Constants.AC_RM_PORT_KEY;
import static eu.project.rapid.common.Constants.ASYMMETRIC_ALG_DEFAULT;
import static eu.project.rapid.common.Constants.ASYMMETRIC_ALG_KEY;
import static eu.project.rapid.common.Constants.AS_PORT_DEFAULT;
import static eu.project.rapid.common.Constants.AS_PORT_KEY;
import static eu.project.rapid.common.Constants.AS_PORT_SSL_DEFAULT;
import static eu.project.rapid.common.Constants.AS_PORT_SSL_KEY;
import static eu.project.rapid.common.Constants.CONFIG_PROPERTIES;
import static eu.project.rapid.common.Constants.CONNECT_PREV_VM_DEFAULT;
import static eu.project.rapid.common.Constants.CONNECT_PREV_VM_KEY;
import static eu.project.rapid.common.Constants.DS_IP_KEY;
import static eu.project.rapid.common.Constants.DS_PORT_DEFAULT;
import static eu.project.rapid.common.Constants.DS_PORT_KEY;
import static eu.project.rapid.common.Constants.RAPID_FOLDER_CLIENT_DEFAULT;
import static eu.project.rapid.common.Constants.RAPID_FOLDER_CLIENT_KEY;
import static eu.project.rapid.common.Constants.RAPID_FOLDER_SERVER_DEFAULT;
import static eu.project.rapid.common.Constants.RAPID_FOLDER_SERVER_KEY;
import static eu.project.rapid.common.Constants.SHARED_PREFS_DEFAULT;
import static eu.project.rapid.common.Constants.SHARED_PREFS_KEY;
import static eu.project.rapid.common.Constants.SLAM_IP_DEFAULT;
import static eu.project.rapid.common.Constants.SLAM_IP_KEY;
import static eu.project.rapid.common.Constants.SLAM_PORT_DEFAULT;
import static eu.project.rapid.common.Constants.SLAM_PORT_KEY;
import static eu.project.rapid.common.Constants.SSL_CA_TRUSTSTORE_DEFAULT;
import static eu.project.rapid.common.Constants.SSL_CA_TRUSTSTORE_KEY;
import static eu.project.rapid.common.Constants.SSL_CERT_ALIAS_DEFAULT;
import static eu.project.rapid.common.Constants.SSL_CERT_ALIAS_KEY;
import static eu.project.rapid.common.Constants.SSL_CERT_PASSW_DEFAULT;
import static eu.project.rapid.common.Constants.SSL_CERT_PASSW_KEY;
import static eu.project.rapid.common.Constants.SSL_KEYSTORE_DEFAULT;
import static eu.project.rapid.common.Constants.SSL_KEYSTORE_KEY;
import static eu.project.rapid.common.Constants.SSL_KEYSTORE_PASSW_DEFAULT;
import static eu.project.rapid.common.Constants.SSL_KEYSTORE_PASSW_KEY;
import static eu.project.rapid.common.Constants.SYMMETRIC_ALG_DEFAULT;
import static eu.project.rapid.common.Constants.SYMMETRIC_ALG_KEY;
import static eu.project.rapid.common.Constants.SYMMETRIC_ALG_KEY_SIZE_DEFAULT;
import static eu.project.rapid.common.Constants.SYMMETRIC_ALG_KEY_SIZE_KEY;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class Configuration {

  // static {
  // Security.insertProviderAt(new BouncyCastleProvider(), 1);
  // }

  private Logger log;

  // Common variables
  private Properties props;
  private String userHomeFolder;
  private String rapidClientFolder;
  private String rapidServerFolder;
  private int asPort;
  private int asPortSsl;
  private String dsIp;
  private int dsPort;
  private String slamIp;
  private int slamPort;

  // Client only variables
  private int acRmPort; // The port where the AC_RM will listen for clients (apps) to ask for info.
  private String sharedPrefsFile; // Contains the full path to the shared prefs file, where the
                                  // AC_RM stores common parameters, like userID, etc.

  // SSL related configuration parameters given by the developer on the configuration file
  private String sslKeyStoreName;
  private String sslKeyStorePassword;
  private String caTrustStoreName;
  private String sslCertAlias;
  private String sslCertPassword;
  private String asymmetricAlg;
  private String symmetricAlg;
  private int symmetricKeySize;

  // SSL related parameters that are created after reading the crypto variables.
  private PublicKey publicKey;
  private PrivateKey privateKey;
  private KeyManagerFactory kmf;
  private SSLContext sslContext;
  private SSLSocketFactory sslFactory;
  private boolean cryptoInitialized = false;

  // Settings parameters that the user chooses when launching the first app, so that the AC_RM
  // starts running and registering with the DS.
  private boolean connectToPrevVm = false;
  private boolean connectSsl = false;

  private String TAG;

  public Configuration(String tag) {
    this.TAG = Configuration.class.getSimpleName() + "-" + tag;
    log = LogManager.getLogger(this.TAG);

    log.info("Reading the configuration file");

    userHomeFolder = System.getProperty("user.home");
    props = new Properties();

    try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_PROPERTIES)) {
      if (is != null) {
        props.load(is);

        // AS initialization parameters
        asPort = Integer.parseInt(props.getProperty(AS_PORT_KEY, AS_PORT_DEFAULT));
        asPortSsl = Integer.parseInt(props.getProperty(AS_PORT_SSL_KEY, AS_PORT_SSL_DEFAULT));
        log.info(AS_PORT_KEY + ": " + asPort);
        log.info(AS_PORT_SSL_KEY + ": " + asPortSsl);

        // DS IP and port
        dsIp = props.getProperty(DS_IP_KEY);
        dsPort = Integer.parseInt(props.getProperty(DS_PORT_KEY, DS_PORT_DEFAULT));
        log.info(DS_IP_KEY + ": " + dsIp);
        log.info(DS_PORT_KEY + ": " + dsPort);

        // SLAM IP and port
        slamIp = props.getProperty(SLAM_IP_KEY, SLAM_IP_DEFAULT);
        slamPort = Integer.parseInt(props.getProperty(SLAM_PORT_KEY, SLAM_PORT_DEFAULT));
        log.info(SLAM_IP_KEY + ": " + slamIp);
        log.info(SLAM_PORT_KEY + ": " + slamPort);

        // RAPID folder configuration
        rapidClientFolder = props.getProperty(RAPID_FOLDER_CLIENT_KEY, RAPID_FOLDER_CLIENT_DEFAULT);
        rapidClientFolder = userHomeFolder + File.separator + rapidClientFolder;

        rapidServerFolder = props.getProperty(RAPID_FOLDER_SERVER_KEY, RAPID_FOLDER_SERVER_DEFAULT);
        rapidServerFolder = userHomeFolder + File.separator + rapidServerFolder;

        // AC_RM configuration listening port
        acRmPort = Integer.parseInt(props.getProperty(AC_RM_PORT_KEY, AC_RM_PORT_DEFAULT));
        sharedPrefsFile = props.getProperty(SHARED_PREFS_KEY, SHARED_PREFS_DEFAULT);
        sharedPrefsFile = rapidClientFolder + File.separator + sharedPrefsFile;

        connectToPrevVm =
            props.getProperty(CONNECT_PREV_VM_KEY, CONNECT_PREV_VM_DEFAULT).equals(Constants.TRUE);

        connectSsl = props.getProperty(Constants.CONNECT_SSL_KEY, Constants.CONNECT_SSL_DEFAULT)
            .equals(Constants.TRUE);

        // SSL parameters
        initializeCrypto();

      } else {
        log.warn("Could not find the configuration file: " + CONFIG_PROPERTIES);
      }
    } catch (IOException e) {
      log.error("Error while opening the configuration file: " + e);
    }
  }

  private void initializeCrypto() {
    sslKeyStoreName = props.getProperty(SSL_KEYSTORE_KEY, SSL_KEYSTORE_DEFAULT);
    sslKeyStorePassword = props.getProperty(SSL_KEYSTORE_PASSW_KEY, SSL_KEYSTORE_PASSW_DEFAULT);
    caTrustStoreName = props.getProperty(SSL_CA_TRUSTSTORE_KEY, SSL_CA_TRUSTSTORE_DEFAULT);
    sslCertAlias = props.getProperty(SSL_CERT_ALIAS_KEY, SSL_CERT_ALIAS_DEFAULT);
    sslCertPassword = props.getProperty(SSL_CERT_PASSW_KEY, SSL_CERT_PASSW_DEFAULT);
    asymmetricAlg = props.getProperty(ASYMMETRIC_ALG_KEY, ASYMMETRIC_ALG_DEFAULT);
    symmetricAlg = props.getProperty(SYMMETRIC_ALG_KEY, SYMMETRIC_ALG_DEFAULT);
    symmetricKeySize = Integer
        .parseInt(props.getProperty(SYMMETRIC_ALG_KEY_SIZE_KEY, SYMMETRIC_ALG_KEY_SIZE_DEFAULT));

    try {
      log.info("KeyStore default type: " + KeyStore.getDefaultType());
      log.info("KeyManagerFactory default algorithm: " + KeyManagerFactory.getDefaultAlgorithm());

      KeyStore trustStore = KeyStore.getInstance("JKS");
      trustStore.load(this.getClass().getClassLoader().getResourceAsStream(caTrustStoreName),
          "passkeystore".toCharArray());
      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);

      // KeyStore keyStore = KeyStore.getInstance("BKS");
      KeyStore keyStore = KeyStore.getInstance("JKS");
      keyStore.load(this.getClass().getClassLoader().getResourceAsStream(sslKeyStoreName),
          sslKeyStorePassword.toCharArray());

      Enumeration<String> aliases = keyStore.aliases();
      while (aliases.hasMoreElements()) {
        log.info(aliases.nextElement());
      }

      kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, sslKeyStorePassword.toCharArray());

      // privateKey = (PrivateKey) keyStore.getKey(sslCertAlias, "kot".toCharArray());
      Certificate cert = keyStore.getCertificate(sslCertAlias);
      publicKey = cert.getPublicKey();

      sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

      sslFactory = sslContext.getSocketFactory();
      log.info("SSL Factory created");

      cryptoInitialized = true;

      // log.info("Certificate: " + cert.toString());
      log.info("Crypto intialized correctly");
      // log.info("PrivateKey algorithm: " + privateKey.getAlgorithm());
      log.info("PublicKey algorithm: " + publicKey.getAlgorithm());

    } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
      log.error("Crypto not initialized: " + e);
      e.printStackTrace();
    } catch (UnrecoverableKeyException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (KeyManagementException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public String toString() {
    return AS_PORT_KEY + ": " + asPort + "\n" + AS_PORT_SSL_KEY + ": " + asPortSsl + "\n";
  }

  /**
   * @return the userHomeFolder
   */
  public String getUserHomeFolder() {
    return userHomeFolder;
  }

  /**
   * @param userHomeFolder the userHomeFolder to set
   */
  public void setUserHomeFolder(String userHomeFolder) {
    this.userHomeFolder = userHomeFolder;
  }

  /**
   * @return the rapidClientFolder
   */
  public String getRapidClientFolder() {
    return rapidClientFolder;
  }

  /**
   * @param rapidFolder the rapidClientFolder to set
   */
  public void setRapidClientFolder(String rapidFolder) {
    this.rapidClientFolder = rapidFolder;
  }

  /**
   * @return the rapidServerFolder
   */
  public String getRapidServerFolder() {
    return rapidServerFolder;
  }

  /**
   * @param rapidFolder the rapidServerFolder to set
   */
  public void setRapidServerFolder(String rapidFolder) {
    this.rapidServerFolder = rapidFolder;
  }

  /**
   * @return the asPort
   */
  public int getAsPort() {
    return asPort;
  }

  /**
   * @param asPort the asPort to set
   */
  public void setAsPort(int asPort) {
    this.asPort = asPort;
  }

  /**
   * @return the asPortSsl
   */
  public int getAsPortSsl() {
    return asPortSsl;
  }

  /**
   * @param asPortSsl the asPortSsl to set
   */
  public void setAsPortSsl(int asPortSsl) {
    this.asPortSsl = asPortSsl;
  }

  /**
   * @return the dsIp
   */
  public String getDsIp() {
    return dsIp;
  }

  /**
   * @param dsIp the dsIp to set
   */
  public void setDsIp(String dsIp) {
    this.dsIp = dsIp;
  }

  /**
   * @return the dsPort
   */
  public int getDsPort() {
    return dsPort;
  }

  /**
   * @param dsPort the dsPort to set
   */
  public void setDsPort(int dsPort) {
    this.dsPort = dsPort;
  }

  /**
   * @return the slamIp
   */
  public String getSlamIp() {
    return slamIp;
  }

  /**
   * @param slamIp the slamIp to set
   */
  public void setSlamIp(String slamIp) {
    this.slamIp = slamIp;
  }

  /**
   * @return the slamPort
   */
  public int getSlamPort() {
    return slamPort;
  }

  /**
   * @param slamPort the slamPort to set
   */
  public void setSlamPort(int slamPort) {
    this.slamPort = slamPort;
  }

  /**
   * @return the sslKeyStoreName
   */
  public String getSslKeyStoreName() {
    return sslKeyStoreName;
  }

  /**
   * @param sslKeyStoreName the sslKeyStoreName to set
   */
  public void setSslKeyStoreName(String sslKeyStoreName) {
    this.sslKeyStoreName = sslKeyStoreName;
  }

  /**
   * @return the caTrustStoreName
   */
  public String getCaTrustStoreName() {
    return caTrustStoreName;
  }

  /**
   * @param caTrustStoreName the caTrustStoreName to set
   */
  public void setCaTrustStoreName(String caTrustStoreName) {
    this.caTrustStoreName = caTrustStoreName;
  }

  /**
   * @return the sslCertAlias
   */
  public String getSslCertAlias() {
    return sslCertAlias;
  }

  /**
   * @param sslCertAlias the sslCertAlias to set
   */
  public void setSslCertAlias(String sslCertAlias) {
    this.sslCertAlias = sslCertAlias;
  }

  /**
   * @return the sslCertPassword
   */
  public String getSslCertPassword() {
    return sslCertPassword;
  }

  /**
   * @param sslCertPassword the sslCertPassword to set
   */
  public void setSslCertPassword(String sslCertPassword) {
    this.sslCertPassword = sslCertPassword;
  }

  /**
   * @return the asymmetricAlg
   */
  public String getAsymmetricAlg() {
    return asymmetricAlg;
  }

  /**
   * @param asymmetricAlg the asymmetricAlg to set
   */
  public void setAsymmetricAlg(String asymmetricAlg) {
    this.asymmetricAlg = asymmetricAlg;
  }

  /**
   * @return the symmetricAlg
   */
  public String getSymmetricAlg() {
    return symmetricAlg;
  }

  /**
   * @param symmetricAlg the symmetricAlg to set
   */
  public void setSymmetricAlg(String symmetricAlg) {
    this.symmetricAlg = symmetricAlg;
  }

  /**
   * @return the symmetricKeySize
   */
  public int getSymmetricKeySize() {
    return symmetricKeySize;
  }

  /**
   * @param symmetricKeySize the symmetricKeySize to set
   */
  public void setSymmetricKeySize(int symmetricKeySize) {
    this.symmetricKeySize = symmetricKeySize;
  }

  /**
   * @return the publicKey
   */
  public PublicKey getPublicKey() {
    return publicKey;
  }

  /**
   * @param publicKey the publicKey to set
   */
  public void setPublicKey(PublicKey publicKey) {
    this.publicKey = publicKey;
  }

  /**
   * @return the privateKey
   */
  public PrivateKey getPrivateKey() {
    return privateKey;
  }

  /**
   * @param privateKey the privateKey to set
   */
  public void setPrivateKey(PrivateKey privateKey) {
    this.privateKey = privateKey;
  }

  /**
   * @return the kmf
   */
  public KeyManagerFactory getKmf() {
    return kmf;
  }

  /**
   * @param kmf the kmf to set
   */
  public void setKmf(KeyManagerFactory kmf) {
    this.kmf = kmf;
  }

  /**
   * @return the sslContext
   */
  public SSLContext getSslContext() {
    return sslContext;
  }

  /**
   * @param sslContext the sslContext to set
   */
  public void setSslContext(SSLContext sslContext) {
    this.sslContext = sslContext;
  }

  /**
   * @return the sslFactory
   */
  public SSLSocketFactory getSslFactory() {
    return sslFactory;
  }

  /**
   * @param sslFactory the sslFactory to set
   */
  public void setSslFactory(SSLSocketFactory sslFactory) {
    this.sslFactory = sslFactory;
  }

  /**
   * @return the cryptoInitialized
   */
  public boolean isCryptoInitialized() {
    return cryptoInitialized;
  }

  /**
   * @param cryptoInitialized the cryptoInitialized to set
   */
  public void setCryptoInitialized(boolean cryptoInitialized) {
    this.cryptoInitialized = cryptoInitialized;
  }

  /**
   * @return the acRmPort
   */
  public int getAcRmPort() {
    return acRmPort;
  }

  /**
   * @param acRmPort the acRmPort to set
   */
  public void setAcRmPort(int acRmPort) {
    this.acRmPort = acRmPort;
  }

  /**
   * @return the fulle path to the sharedPrefsFile
   */
  public String getSharedPrefsFile() {
    return sharedPrefsFile;
  }

  /**
   * @param sharedPrefsFile the sharedPrefsFile to set
   */
  public void setSharedPrefsFile(String sharedPrefsFile) {
    this.sharedPrefsFile = sharedPrefsFile;
  }

  /**
   * @return the connectToPrevVm
   */
  public boolean isConnectToPrevVm() {
    return connectToPrevVm;
  }

  /**
   * @param connectToPrevVm the connectToPrevVm to set
   */
  public void setConnectToPrevVm(boolean connectToPrevVm) {
    this.connectToPrevVm = connectToPrevVm;
  }

  /**
   * @return the connectSsl
   */
  public boolean isConnectSsl() {
    return connectSsl;
  }

  /**
   * @param connectSsl the connectSsl to set
   */
  public void setConnectSsl(boolean connectSsl) {
    this.connectSsl = connectSsl;
  }
}
