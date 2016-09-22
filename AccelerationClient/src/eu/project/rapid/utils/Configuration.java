package eu.project.rapid.utils;

import static eu.project.rapid.utils.Constants.AC_RM_PORT_KEY;
import static eu.project.rapid.utils.Constants.ASYMMETRIC_ALG_DEFAULT;
import static eu.project.rapid.utils.Constants.ASYMMETRIC_ALG_KEY;
import static eu.project.rapid.utils.Constants.AS_PORT_KEY;
import static eu.project.rapid.utils.Constants.AS_PORT_SSL_KEY;
import static eu.project.rapid.utils.Constants.CONFIG_PROPERTIES;
import static eu.project.rapid.utils.Constants.CONNECT_PREV_VM_DEFAULT;
import static eu.project.rapid.utils.Constants.CONNECT_PREV_VM_KEY;
import static eu.project.rapid.utils.Constants.DS_IP_KEY;
import static eu.project.rapid.utils.Constants.DS_PORT_KEY;
import static eu.project.rapid.utils.Constants.RAPID_FOLDER_CLIENT_DEFAULT;
import static eu.project.rapid.utils.Constants.RAPID_FOLDER_CLIENT_KEY;
import static eu.project.rapid.utils.Constants.RAPID_FOLDER_SERVER_DEFAULT;
import static eu.project.rapid.utils.Constants.RAPID_FOLDER_SERVER_KEY;
import static eu.project.rapid.utils.Constants.SHARED_PREFS_DEFAULT;
import static eu.project.rapid.utils.Constants.SHARED_PREFS_KEY;
import static eu.project.rapid.utils.Constants.SLAM_IP_DEFAULT;
import static eu.project.rapid.utils.Constants.SLAM_IP_KEY;
import static eu.project.rapid.utils.Constants.SLAM_PORT_KEY;
import static eu.project.rapid.utils.Constants.SSL_CA_TRUSTSTORE_DEFAULT;
import static eu.project.rapid.utils.Constants.SSL_CA_TRUSTSTORE_KEY;
import static eu.project.rapid.utils.Constants.SSL_CERT_ALIAS_DEFAULT;
import static eu.project.rapid.utils.Constants.SSL_CERT_ALIAS_KEY;
import static eu.project.rapid.utils.Constants.SSL_CERT_PASSW_DEFAULT;
import static eu.project.rapid.utils.Constants.SSL_CERT_PASSW_KEY;
import static eu.project.rapid.utils.Constants.SSL_KEYSTORE_DEFAULT;
import static eu.project.rapid.utils.Constants.SSL_KEYSTORE_KEY;
import static eu.project.rapid.utils.Constants.SSL_KEYSTORE_PASSW_DEFAULT;
import static eu.project.rapid.utils.Constants.SSL_KEYSTORE_PASSW_KEY;
import static eu.project.rapid.utils.Constants.SYMMETRIC_ALG_DEFAULT;
import static eu.project.rapid.utils.Constants.SYMMETRIC_ALG_KEY;
import static eu.project.rapid.utils.Constants.SYMMETRIC_ALG_KEY_SIZE_DEFAULT;
import static eu.project.rapid.utils.Constants.SYMMETRIC_ALG_KEY_SIZE_KEY;

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

import eu.project.rapid.common.Clone;
import eu.project.rapid.common.RapidConstants;
import eu.project.rapid.common.RapidConstants.REGIME;
// import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * FIXME: Delete this class and use only configuration from Rapid-Common project.
 * 
 * @author sokol
 *
 */
public final class Configuration {

  private Logger log;
  private REGIME regime;

  private Clone vm;

  // Common variables
  private Properties props;
  private String userHomeFolder;
  private String rapidFolder;
  private String rapidLogFile;

  // DB related variables
  private String rapidDbName;
  private String dbDriver;
  private String dbUrl;
  private String dbUser;
  private String dbPass;

  private int asPort;
  private int asPortSsl;

  private String dsIp;
  private int dsPort;

  private String slamIp;
  private int slamPort;

  private String vmmIp;
  private int vmmPort;

  private String gvirtusIp;
  private int gvirtusPort;

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
  private int clonePortBandwidthTest = 4321;

  private String TAG;

  public Configuration(String callerTag, REGIME regime) {
    this.regime = regime;
    this.TAG = Configuration.class.getSimpleName() + "-" + callerTag + "-" + regime.toString();
    log = LogManager.getLogger(this.TAG);

    log.info("Reading the configuration file");

    userHomeFolder = System.getProperty("user.home");
    props = new Properties();

    try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_PROPERTIES)) {
      if (is != null) {
        props.load(is);

        // AS initialization parameters
        asPort = Integer.parseInt(
            props.getProperty(AS_PORT_KEY, Integer.toString(RapidConstants.DEFAULT_VM_PORT)));
        asPortSsl = Integer.parseInt(props.getProperty(AS_PORT_SSL_KEY,
            Integer.toString(RapidConstants.DEFAULT_VM_PORT_SSL)));
        log.info(AS_PORT_KEY + ": " + asPort);
        log.info(AS_PORT_SSL_KEY + ": " + asPortSsl);

        // DS IP and port
        dsIp = props.getProperty(DS_IP_KEY);
        dsPort = Integer.parseInt(
            props.getProperty(DS_PORT_KEY, Integer.toString(RapidConstants.DEFAULT_DS_PORT)));
        log.info(DS_IP_KEY + ": " + dsIp);
        log.info(DS_PORT_KEY + ": " + dsPort);

        // SLAM IP and port
        slamIp = props.getProperty(SLAM_IP_KEY, SLAM_IP_DEFAULT);
        slamPort = Integer.parseInt(
            props.getProperty(SLAM_PORT_KEY, Integer.toString(RapidConstants.DEFAULT_SLAM_PORT)));
        log.info(SLAM_IP_KEY + ": " + slamIp);
        log.info(SLAM_PORT_KEY + ": " + slamPort);

        // RAPID folder configuration
        if (regime == REGIME.AC) {
          rapidFolder = props.getProperty(RAPID_FOLDER_CLIENT_KEY, RAPID_FOLDER_CLIENT_DEFAULT);
          rapidFolder = userHomeFolder + File.separator + rapidFolder;
        } else if (regime == REGIME.AS) {
          rapidFolder = props.getProperty(RAPID_FOLDER_SERVER_KEY, RAPID_FOLDER_SERVER_DEFAULT);
          rapidFolder = userHomeFolder + File.separator + rapidFolder;
        }

        rapidLogFile =
            props.getProperty(Constants.LOG_FILE_NAME_KEY, Constants.LOG_FILE_NAME_DEFAULT);
        rapidLogFile = rapidFolder + File.separator + rapidLogFile;

        // DB variables
        // rapidDbName = props.getProperty(Constants.DB_FILE_NAME_KEY,
        // Constants.DB_FILE_NAME_DEFAULT);
        // dbDriver =
        // props.getProperty(Constants.DB_JDBC_DRIVER_KEY, Constants.DB_JDBC_DRIVER_DEFAULT);
        // dbUrl = props.getProperty(Constants.DB_URL_KEY, Constants.DB_URL_DEFAULT);
        // // User and pass should not be null, otherwise DB will not work
        // dbUser = props.getProperty(Constants.DB_USER_KEY).trim();
        // dbPass = props.getProperty(Constants.DB_PASS_KEY).trim();
        // assert (dbUser != null && dbPass != null);

        // AC_RM configuration listening port
        acRmPort = Integer.parseInt(
            props.getProperty(AC_RM_PORT_KEY, Integer.toString(RapidConstants.AC_RM_PORT_DEFAULT)));
        sharedPrefsFile = props.getProperty(SHARED_PREFS_KEY, SHARED_PREFS_DEFAULT);
        sharedPrefsFile = rapidFolder + File.separator + sharedPrefsFile;

        connectToPrevVm =
            props.getProperty(CONNECT_PREV_VM_KEY, CONNECT_PREV_VM_DEFAULT).equals(Constants.TRUE);

        // GVirtuS ip and port
        gvirtusIp = props.getProperty(Constants.GVIRTUS_IP_KEY);
        gvirtusPort = Integer.parseInt(props.getProperty(Constants.GVIRTUS_PORT_KEY,
            Integer.toString(RapidConstants.DEFAULT_GVIRTUS_PORT)));

        // GVirtuS ip and port
        vmmIp = props.getProperty(Constants.VMM_IP_KEY);
        vmmPort = Integer.parseInt(props.getProperty(Constants.VMM_PORT_KEY,
            Integer.toString(RapidConstants.DEFAULT_VMM_PORT)));

        // SSL parameters
        connectSsl = props.getProperty(Constants.CONNECT_SSL_KEY, Constants.CONNECT_SSL_DEFAULT)
            .equals(Constants.TRUE);
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
   * @return the rapidFolder
   */
  public String getRapidFolder() {
    return rapidFolder;
  }

  /**
   * @param rapidFolder the rapidFolder to set
   */
  public void setRapidFolder(String rapidFolder) {
    this.rapidFolder = rapidFolder;
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

  /**
   * @return the regime
   */
  public REGIME getRegime() {
    return regime;
  }

  /**
   * @param regime the regime to set
   */
  public void setRegime(REGIME regime) {
    this.regime = regime;
  }

  /**
   * @return the rapidLogFile
   */
  public String getRapidLogFile() {
    return rapidLogFile;
  }

  /**
   * @param rapidLogFile the rapidLogFile to set
   */
  public void setRapidLogFile(String rapidLogFile) {
    this.rapidLogFile = rapidLogFile;
  }

  /**
   * @return the rapidDbName
   */
  public String getRapidDbName() {
    return rapidDbName;
  }

  /**
   * @param rapidDbName the rapidDbName to set
   */
  public void setRapidDbName(String rapidDbName) {
    this.rapidDbName = rapidDbName;
  }

  /**
   * @return the dbDriver
   */
  public String getDbDriver() {
    return dbDriver;
  }

  /**
   * @param dbDriver the dbDriver to set
   */
  public void setDbDriver(String dbDriver) {
    this.dbDriver = dbDriver;
  }

  /**
   * @return the dbUrl
   */
  public String getDbUrl() {
    return dbUrl;
  }

  /**
   * @param dbUrl the dbUrl to set
   */
  public void setDbUrl(String dbUrl) {
    this.dbUrl = dbUrl;
  }

  /**
   * @return the dbUser
   */
  public String getDbUser() {
    return dbUser;
  }

  /**
   * @param dbUser the dbUser to set
   */
  public void setDbUser(String dbUser) {
    this.dbUser = dbUser;
  }

  /**
   * @return the dbPass
   */
  public String getDbPass() {
    return dbPass;
  }

  /**
   * @param dbPass the dbPass to set
   */
  public void setDbPass(String dbPass) {
    this.dbPass = dbPass;
  }

  /**
   * @return the vm
   */
  public Clone getVm() {
    return vm;
  }

  /**
   * @param vm the vm to set
   */
  public void setVm(Clone vm) {
    this.vm = vm;
  }


  /**
   * @return the clonePortBandwidthTest
   */
  public int getClonePortBandwidthTest() {
    return clonePortBandwidthTest;
  }

  /**
   * @param clonePortBandwidthTest the clonePortBandwidthTest to set
   */
  public void setClonePortBandwidthTest(int clonePortBandwidthTest) {
    this.clonePortBandwidthTest = clonePortBandwidthTest;
  }

  /**
   * @return the gvirtusIp
   */
  public String getGvirtusIp() {
    return gvirtusIp;
  }

  /**
   * @param gvirtusIp the gvirtusIp to set
   */
  public void setGvirtusIp(String gvirtusIp) {
    this.gvirtusIp = gvirtusIp;
  }

  /**
   * @return the gvirtusPort
   */
  public int getGvirtusPort() {
    return gvirtusPort;
  }

  /**
   * @param gvirtusPort the gvirtusPort to set
   */
  public void setGvirtusPort(int gvirtusPort) {
    this.gvirtusPort = gvirtusPort;
  }

  /**
   * @return the vmmIp
   */
  public String getVmmIp() {
    return vmmIp;
  }

  /**
   * @param vmmIp the vmmIp to set
   */
  public void setVmmIp(String vmmIp) {
    this.vmmIp = vmmIp;
  }

  /**
   * @return the vmmPort
   */
  public int getVmmPort() {
    return vmmPort;
  }

  /**
   * @param vmmPort the vmmPort to set
   */
  public void setVmmPort(int vmmPort) {
    this.vmmPort = vmmPort;
  }
}
