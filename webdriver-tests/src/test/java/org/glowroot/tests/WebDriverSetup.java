/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.tests;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Properties;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.common.SauceOnDemandSessionIdProvider;
import com.saucelabs.junit.SauceOnDemandTestWatcher;
import kr.motd.maven.os.Detector;
import org.junit.rules.TestWatcher;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.it.harness.impl.LocalContainer;

public class WebDriverSetup {

    protected static final boolean useCentral =
            Boolean.getBoolean("glowroot.internal.webdriver.useCentral");

    private static final boolean USE_FIREFOX = false;

    private static final String GECKO_DRIVER_VERSION = "0.11.1";

    private static final Logger logger = LoggerFactory.getLogger(WebDriverSetup.class);

    static {
        // shorter time so aggregates and gauges will be collected during BasicSmokeIT
        System.setProperty("glowroot.internal.rollup.0.intervalMillis", "1000");
        System.setProperty("glowroot.internal.gaugeCollectionIntervalMillis", "1000");
    }

    public static WebDriverSetup create() throws Exception {
        if (!SharedSetupRunListener.useSharedSetup()) {
            return createSetup(false);
        }
        WebDriverSetup sharedSetup = SharedSetupRunListener.getSharedSetup();
        if (sharedSetup == null) {
            sharedSetup = createSetup(true);
            SharedSetupRunListener.setSharedSetup(sharedSetup);
        }
        return sharedSetup;
    }

    private final Container container;
    private final int uiPort;
    private final boolean shared;
    private WebDriver driver;

    private String remoteWebDriverSessionId;

    private WebDriverSetup(Container container, int uiPort, boolean shared, WebDriver driver)
            throws Exception {
        this.container = container;
        this.uiPort = uiPort;
        this.shared = shared;
        this.driver = driver;
    }

    public void close() throws Exception {
        close(false);
    }

    public void close(boolean evenIfShared) throws Exception {
        if (shared && !evenIfShared) {
            // this is the shared setup and will be closed at the end of the run
            return;
        }
        if (driver != null) {
            driver.quit();
        }
        container.close();
        if (useCentral) {
            Class<?> bootstrapClass = Class.forName("org.glowroot.central.Bootstrap");
            Method mainMethod = bootstrapClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) new String[] {"stop"});
            CassandraWrapper.stop();
        }
    }

    public Container getContainer() {
        return container;
    }

    public int getUiPort() {
        return uiPort;
    }

    public WebDriver getDriver() {
        return driver;
    }

    public void beforeEachTest(String testName, ScreenshotOnExceptionRule screenshotOnExceptionRule)
            throws Exception {
        if (SauceLabs.useSauceLabs()) {
            // need separate webdriver instance per test in order to report each test separately in
            // saucelabs
            driver = SauceLabs.getWebDriver(testName);
            // need to capture sessionId since it is needed in sauceLabsTestWatcher, after
            // driver.quit() is called
            remoteWebDriverSessionId = ((RemoteWebDriver) driver).getSessionId().toString();
        } else {
            screenshotOnExceptionRule.setDriver(driver);
        }
    }

    public void afterEachTest() throws Exception {
        if (SauceLabs.useSauceLabs()) {
            driver.quit();
        }
        container.checkAndReset();
    }

    public TestWatcher getSauceLabsTestWatcher() {
        if (!SauceLabs.useSauceLabs()) {
            return new TestWatcher() {};
        }
        String sauceUsername = System.getenv("SAUCE_USERNAME");
        String sauceAccessKey = System.getenv("SAUCE_ACCESS_KEY");
        SauceOnDemandAuthentication authentication =
                new SauceOnDemandAuthentication(sauceUsername, sauceAccessKey);
        SauceOnDemandSessionIdProvider sessionIdProvider = new SauceOnDemandSessionIdProvider() {
            @Override
            public String getSessionId() {
                return remoteWebDriverSessionId;
            }
        };
        return new SauceOnDemandTestWatcher(sessionIdProvider, authentication);
    }

    private static WebDriverSetup createSetup(boolean shared) throws Exception {
        int uiPort = getAvailablePort();
        File testDir = Files.createTempDir();
        Container container;
        if (useCentral) {
            CassandraWrapper.start();
            Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
            Session session = cluster.newSession();
            session.execute("create keyspace if not exists glowroot_unit_tests with replication ="
                    + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
            session.execute("use glowroot_unit_tests");
            session.execute("drop table if exists agent");
            session.execute("drop table if exists agent_rollup");
            session.execute("drop table if exists user");
            session.execute("drop table if exists role");
            session.execute("drop table if exists central_config");
            session.close();
            cluster.close();
            container = createCentralAndContainer(uiPort, testDir);
        } else {
            container = createContainer(uiPort, testDir);
        }
        if (SauceLabs.useSauceLabs()) {
            return new WebDriverSetup(container, uiPort, shared, null);
        } else {
            // single webdriver instance for much better performance
            WebDriver driver;
            if (USE_FIREFOX) {
                File geckoDriverExecutable = downloadGeckoDriverIfNeeded();
                System.setProperty("webdriver.gecko.driver",
                        geckoDriverExecutable.getAbsolutePath());
                driver = new FirefoxDriver();
            } else {
                driver = new JBrowserDriver();
            }
            // 768 is bootstrap media query breakpoint for screen-sm-min
            // 992 is bootstrap media query breakpoint for screen-md-min
            // 1200 is bootstrap media query breakpoint for screen-lg-min
            driver.manage().window().setSize(new Dimension(1200, 800));
            return new WebDriverSetup(container, uiPort, shared, driver);
        }
    }

    private static Container createContainer(int uiPort, File testDir) throws Exception {
        File adminFile = new File(testDir, "admin.json");
        Files.write("{\"web\":{\"port\":" + uiPort + "}}", adminFile, Charsets.UTF_8);
        if (Containers.useJavaagent()) {
            return new JavaagentContainer(testDir, true,
                    ImmutableList.of("-Dglowroot.collector.host="));
        } else {
            return new LocalContainer(testDir, true,
                    ImmutableMap.of("glowroot.collector.host", ""));
        }
    }

    private static Container createCentralAndContainer(int uiPort, File testDir) throws Exception {
        int grpcPort = getAvailablePort();
        PrintWriter props = new PrintWriter("glowroot-central.properties");
        props.println("cassandra.keyspace=glowroot_unit_tests");
        props.println("grpc.port=" + grpcPort);
        props.println("ui.port=" + uiPort);
        props.close();
        Class<?> bootstrapClass = Class.forName("org.glowroot.central.Bootstrap");
        Method mainMethod = bootstrapClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) new String[] {"start"});
        if (Containers.useJavaagent()) {
            // -Xmx is to limit memory usage on travis-ci builds
            return new JavaagentContainer(testDir, false,
                    ImmutableList.of("-Dglowroot.collector.host=localhost",
                            "-Dglowroot.collector.port=" + grpcPort, "-Xmx64m"));
        } else {
            return new LocalContainer(testDir, false,
                    ImmutableMap.of("glowroot.collector.host", "localhost",
                            "glowroot.collector.port", Integer.toString(grpcPort)));
        }
    }

    private static int getAvailablePort() throws Exception {
        if (SauceLabs.useSauceLabs()) {
            // glowroot must listen on one of the ports that sauce connect proxies
            // see https://saucelabs.com/docs/connect#localhost
            return 4000;
        } else {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }

    private static File downloadGeckoDriverIfNeeded() throws IOException {
        MyDetector detector = new MyDetector();
        Properties props = detector.detect();
        String osDetectedName = props.getProperty("os.detected.name");
        String osDetectedArch = props.getProperty("os.detected.arch");
        int bits;
        if (osDetectedArch.endsWith("_64")) {
            bits = 64;
        } else if (osDetectedArch.endsWith("_32")) {
            bits = 32;
        } else {
            throw new IllegalStateException("Unexpected os.detected.arch: " + osDetectedArch);
        }
        String optionalExt;
        String downloadFilenameSuffix;
        String downloadFilenameExt;
        Archiver archiver;
        if (osDetectedName.equals("linux")) {
            optionalExt = "";
            downloadFilenameSuffix = "linux" + bits;
            downloadFilenameExt = "tar.gz";
            archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP);
        } else if (osDetectedName.equals("osx")) {
            optionalExt = "";
            downloadFilenameSuffix = "macos";
            downloadFilenameExt = "tar.gz";
            archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP);
        } else if (osDetectedName.equals("windows")) {
            optionalExt = ".exe";
            downloadFilenameSuffix = "win" + bits;
            downloadFilenameExt = "zip";
            archiver = ArchiverFactory.createArchiver(ArchiveFormat.ZIP);
        } else {
            throw new IllegalStateException(
                    "Unsupported OS for running geckodriver: " + osDetectedName);
        }
        File targetDir = new File("target");
        targetDir.mkdir();
        File geckoDriverExecutable = new File(targetDir, "geckodriver" + optionalExt);
        if (!geckoDriverExecutable.exists()) {
            downloadAndExtractGeckoDriver(targetDir, downloadFilenameSuffix, downloadFilenameExt,
                    archiver);

        }
        return geckoDriverExecutable;
    }

    private static void downloadAndExtractGeckoDriver(File directory, String downloadFilenameSuffix,
            String downloadFilenameExt, Archiver archiver) throws IOException {
        // using System.out to make sure user sees why there is a delay here
        System.out.print("Downloading Mozilla geckodriver " + GECKO_DRIVER_VERSION + " ...");
        URL url = new URL("https://github.com/mozilla/geckodriver/releases/download/v"
                + GECKO_DRIVER_VERSION + "/geckodriver-v" + GECKO_DRIVER_VERSION + "-"
                + downloadFilenameSuffix + "." + downloadFilenameExt);
        InputStream in = url.openStream();
        File archiveFile = File.createTempFile("geckodriver-" + GECKO_DRIVER_VERSION + "-",
                "." + downloadFilenameExt);
        Files.asByteSink(archiveFile).writeFrom(in);
        in.close();
        archiver.extract(archiveFile, directory);
        archiveFile.delete();
        System.out.println(" OK");
    }

    private static class MyDetector extends Detector {

        private Properties detect() {
            Properties props = new Properties();
            super.detect(props, ImmutableList.<String>of());
            return props;
        }

        @Override
        protected void log(String message) {
            logger.info(message);
        }

        @Override
        protected void logProperty(String name, String value) {
            if (logger.isInfoEnabled()) {
                logger.info(name + ": " + value);
            }
        }
    }
}
