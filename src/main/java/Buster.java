import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import ui.DirPanel;
import ui.DnsPanel;
import ui.VhostPanel;

import javax.swing.*;

/**
 *
 * three modes:
 *   dir   recursive directory/file bruteforce
 *   vhost virtual host enumeration
 *   dns   active subdomain bruteforce
 */
public class Buster implements BurpExtension {

    private static final long UNLOAD_WAIT_MS = 2000;

    private DirPanel dir;
    private VhostPanel vhost;
    private DnsPanel dns;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Buster");

        dir   = new DirPanel(api);
        vhost = new VhostPanel(api);
        dns   = new DnsPanel(api);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dir",   dir.getComponent());
        tabs.addTab("Vhost", vhost.getComponent());
        tabs.addTab("DNS",   dns.getComponent());

        api.userInterface().registerSuiteTab("Buster", tabs);

        // stop running scans and give their threads one shared bounded window (2s total) to exit, BApp Store requirement
        api.extension().registerUnloadingHandler(() -> {
            dir.cancel();
            vhost.cancel();
            dns.cancel();
            long deadline = System.currentTimeMillis() + UNLOAD_WAIT_MS;
            dir.cancelAndWait(Math.max(1, deadline - System.currentTimeMillis()));
            vhost.cancelAndWait(Math.max(1, deadline - System.currentTimeMillis()));
            dns.cancelAndWait(Math.max(1, deadline - System.currentTimeMillis()));
            api.logging().logToOutput("Buster unloaded");
        });

        api.logging().logToOutput("Buster loaded: Dir, Vhost and DNS modes are in the Buster tab");
    }
}
