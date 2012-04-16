package com.cloudbees.jenkins.plugins.freeplugins;

import hudson.BulkChange;
import hudson.Extension;
import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.lifecycle.RestartNotSupportedException;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.util.PersistedList;
import hudson.util.TimeUnit2;
import hudson.util.VersionNumber;
import org.jvnet.hudson.reactor.Milestone;
import org.jvnet.localizer.Localizable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs the custom update site.
 */
public class PluginImpl extends Plugin {

    public static enum Milestones implements Milestone {
        UPDATE_CENTER_CONFIGURED
    }

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());

    private static final String CLOUDBEES_UPDATE_CENTER_URL =
            "http://jenkins-updates.apps.cloudbees.com/update-center/cloudbees-proprietary/update-center.json";

    private static final Set<String> cloudBeesUpdateCenterUrls = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_URL
    ));

    private static final String CLOUDBEES_UPDATE_CENTER_ID = "cloudbees-proprietary";

    private static final Set<String> cloudBeesUpdateCenterIds = new HashSet<String>(Arrays.asList(
            CLOUDBEES_UPDATE_CENTER_ID
    ));

    private static final Dependency[] CLOUDBEES_FREE_PLUGINS = {
            require("cloudbees-credentials"),
            require("cloudbees-registration"),
            require("cloudbees-license", "2.6"),
            require("free-license", "1.3"),
            optional("nectar-license", "2.6"),
            require("cloudbees-folder", "2.1"),
            require("cloudbees-cloud-backup"),
            require("cloudbees-wasted-minutes-tracker")
    };

    private static final List<Dependency> pendingPluginInstalls = new ArrayList<Dependency>();

    /**
     * Guarded by {@link #pendingPluginInstalls}.
     */
    private static DelayedInstaller worker = null;

    private static volatile Localizable status = null;

    private static volatile boolean statusImportant = false;

    private String installedVersion = null;

    public PluginImpl() {
    }

    @Override
    public void start() throws Exception {
        LOGGER.log(Level.INFO, "Started...");
        try {
            load();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Could not deserialize state, assuming the plugins need re-installation", e);
            installedVersion = null;
        }
    }

    public boolean isInstalled() {
        if (installedVersion == null) {
            return false;
        }
        try {
            PluginWrapper pluginWrapper = Hudson.getInstance().getPluginManager().getPlugin(getClass());
            String targetVersion = getVersionString(pluginWrapper);
            LOGGER.log(Level.FINE, "Installed version = {0}. Target version = {1}",
                    new Object[]{installedVersion, targetVersion});
            return !new VersionNumber(installedVersion).isOlderThan(new VersionNumber(targetVersion));
        } catch (Throwable t) {
            // if in doubt, it's not installed
            return false;
        }
    }

    public void setInstalled(boolean installed) {
        boolean changed = false;
        if (installed) {
            PluginWrapper pluginWrapper = Hudson.getInstance().getPluginManager().getPlugin(getClass());
            String version = getVersionString(pluginWrapper);
            if (!version.equals(installedVersion)) {
                this.installedVersion = version;
                changed = true;
            }
        } else {
            if (installedVersion != null) {
                installedVersion = null;
                changed = true;
            }
        }
        if (changed) {
            try {
                save();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Could not serialize state. If any of the free plugins are uninstalled, "
                                + "they may be reinstalled on next restart.",
                        e);
            }
        }
    }

    private String getVersionString(PluginWrapper pluginWrapper) {
        String version = pluginWrapper.getVersionNumber().toString();
        int i = version.indexOf(' ');
        version = i == -1 ? version : version.substring(0, i);
        return version;
    }

    public static Localizable getStatus() {
        return status;
    }

    public static boolean isStatusImportant() {
        return statusImportant;
    }

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, attains = "cloudbees-update-center-configured")
    public static void addUpdateCenter() throws Exception {
        LOGGER.log(Level.FINE, "Checking that the CloudBees update center has been configured.");
        UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
        PersistedList<UpdateSite> sites = updateCenter.getSites();
        if (sites.isEmpty()) {
            // likely the list has not been loaded yet
            updateCenter.load();
            sites = updateCenter.getSites();
        }

        boolean found = false;
        List<UpdateSite> forRemoval = new ArrayList<UpdateSite>();
        for (UpdateSite site : sites) {
            LOGGER.log(Level.FINEST, "Update site {0} class {1} url {2}",
                    new Object[]{site.getId(), site.getClass(), site.getUrl()});
            if (cloudBeesUpdateCenterUrls.contains(site.getUrl()) || cloudBeesUpdateCenterIds.contains(site.getId())
                    || site instanceof CloudBeesUpdateSite) {
                LOGGER.log(Level.FINE, "Found possible match:\n  class = {0}\n  url = {1}\n  id = {2}",
                        new Object[]{site.getClass().getName(), site.getUrl(), site.getId()});
                boolean valid = site instanceof CloudBeesUpdateSite
                        && CLOUDBEES_UPDATE_CENTER_URL.equals(site.getUrl())
                        && CLOUDBEES_UPDATE_CENTER_ID.equals(site.getId());
                if (found || !valid) {
                    // remove old and duplicate entries
                    forRemoval.add(site);
                }
                found = found || valid;
            }
        }

        // now make the changes if we have any to make
        LOGGER.log(Level.FINE, "Found={0}\nRemoving={1}", new Object[]{found, forRemoval});
        if (!found || !forRemoval.isEmpty()) {
            BulkChange bc = new BulkChange(updateCenter);
            try {
                for (UpdateSite site : forRemoval) {
                    LOGGER.info("Removing legacy CloudBees Update Center from list of update centers");
                    sites.remove(site);
                }
                if (sites.isEmpty()) {
                    LOGGER.info("Adding Default Update Center to list of update centers as it was missing");
                    sites.add(new UpdateSite("default", "http://updates.jenkins-ci.org/update-center.json"));
                }
                if (!found) {
                    LOGGER.info("Adding CloudBees Update Center to list of update centers");
                    sites.add(new CloudBeesUpdateSite(CLOUDBEES_UPDATE_CENTER_ID, CLOUDBEES_UPDATE_CENTER_URL));
                }
            } finally {
                bc.commit();
            }
        }
    }

    @Initializer(requires = "cloudbees-update-center-configured")
    public static void installCorePlugins() {
        LOGGER.log(Level.INFO, "Checking that the CloudBees plugins have been installed.");
        PluginImpl instance = Hudson.getInstance().getPlugin(PluginImpl.class);
        if (instance != null && instance.isInstalled()) {
            LOGGER.info("Core plugins installation previously completed, will not check or reinstall");
            return;
        }
        for (Dependency pluginArtifactId : CLOUDBEES_FREE_PLUGINS) {
            LOGGER.log(Level.FINE, "Checking {0}.", pluginArtifactId.name);
            PluginWrapper plugin = Hudson.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
            if (plugin == null && !pluginArtifactId.optional) {
                // not installed and mandatory
                scheduleInstall(pluginArtifactId);
            } else if (plugin != null && (pluginArtifactId.version != null || plugin.getVersion() == null)) {
                // already installed
                if (plugin.getVersionNumber().compareTo(pluginArtifactId.version) < 0) {
                    // but older version
                    scheduleInstall(pluginArtifactId);
                }
            }
        }
        boolean finished;
        synchronized (pendingPluginInstalls) {
            finished = pendingPluginInstalls.isEmpty();
            if (!finished && (worker == null || !worker.isAlive())) {
                status = Messages._Notice_downloadUCMetadata();
                LOGGER.info("Starting background thread for core plugin installation");
                worker = new DelayedInstaller();
                worker.setDaemon(true);
                worker.start();
            } else {
                LOGGER.log(Level.INFO, "Nothing to do");
            }
        }
        if (finished && instance != null) {
            instance.setInstalled(true);
        }
    }

    private static void scheduleInstall(Dependency pluginArtifactId) {
        synchronized (pendingPluginInstalls) {
            LOGGER.log(Level.FINE, "Scheduling installation of {0}", pluginArtifactId.name);
            pendingPluginInstalls.add(pluginArtifactId);
        }
    }

    @Extension
    public static class DelayedInstaller extends Thread {

        private long nextWarning;

        @Override
        public void run() {
            nextWarning = 0;
            try {
                boolean loop = true;
                while (loop) {
                    LOGGER.fine("Background thread for core plugin installation awake");
                    try {
                        UpdateSite cloudbeesSite =
                                Hudson.getInstance().getUpdateCenter().getSite(CLOUDBEES_UPDATE_CENTER_ID);
                        if (cloudbeesSite.getDataTimestamp() > -1) {
                            loop = progressPluginInstalls(cloudbeesSite);
                        } else {
                            status = Messages._Notice_downloadUCMetadata();
                        }

                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        // ignore
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                }
                if (!loop) {
                    statusImportant = true;
                    try {
                        status = Messages._Notice_scheduledRestart();
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                        Hudson.getInstance().safeRestart();
                    } catch (RestartNotSupportedException exception) {
                        // ignore if restart is not allowed
                        status = Messages._Notice_restartRequired();
                    }
                }
            } finally {
                LOGGER.info("Background thread for core plugin installation finished.");
                boolean finished;
                synchronized (pendingPluginInstalls) {
                    if (worker == this) {
                        worker = null;
                    }
                    finished = pendingPluginInstalls.isEmpty();
                }
                PluginImpl instance = Hudson.getInstance().getPlugin(PluginImpl.class);
                if (finished && instance != null) {
                    instance.setInstalled(true);
                }
            }
        }

        private boolean progressPluginInstalls(UpdateSite cloudbeesSite) {
            synchronized (pendingPluginInstalls) {
                while (!pendingPluginInstalls.isEmpty()) {
                    Dependency pluginArtifactId = pendingPluginInstalls.get(0);
                    UpdateSite.Plugin p =
                            Hudson.getInstance().getUpdateCenter().getPlugin(pluginArtifactId.name);
                    if (p == null) {
                        if (System.currentTimeMillis() > nextWarning) {
                            LOGGER.log(Level.WARNING,
                                    "Cannot find core plugin {0}, the CloudBees free plugins cannot be "
                                            + "installed without this core plugin. Will try again later.",
                                    pluginArtifactId.name);
                            nextWarning = System.currentTimeMillis() + TimeUnit2.HOURS.toMillis(1);
                        }
                        break;
                    } else if (p.getInstalled() != null && p.getInstalled().isEnabled()) {
                        PluginWrapper plugin = Hudson.getInstance().getPluginManager().getPlugin(pluginArtifactId.name);
                        if (plugin != null && plugin.getVersionNumber().compareTo(pluginArtifactId.version) < 0) {
                            LOGGER.info("Upgrading CloudBees plugin: " + pluginArtifactId);
                            status = Messages._Notice_upgradingPlugin(p.getDisplayName(), p.version);
                            try {
                                p.deploy().get();
                                LOGGER.info("Upgraded CloudBees plugin: " + pluginArtifactId + " to " + p.version);
                                pendingPluginInstalls.remove(0);
                                nextWarning = 0;
                                status = Messages._Notice_upgradedPlugin(p.getDisplayName(), p.version);
                            } catch (Throwable e) {
                                // ignore
                            }

                        } else {
                            LOGGER.info("Detected previous installation of CloudBees plugin: " + pluginArtifactId);
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                        }
                    } else {
                        LOGGER.info("Installing CloudBees plugin: " + pluginArtifactId);
                        status = Messages._Notice_installingPlugin(p.getDisplayName());
                        try {
                            p.deploy().get();
                            LOGGER.info("Installed CloudBees plugin: " + pluginArtifactId);
                            pendingPluginInstalls.remove(0);
                            nextWarning = 0;
                            status = Messages._Notice_installedPlugin(p.getDisplayName());
                        } catch (Throwable e) {
                            // ignore
                        }
                    }
                }
                return !pendingPluginInstalls.isEmpty();
            }
        }
    }

    static {
        UpdateCenter.XSTREAM.alias("cloudbees", CloudBeesUpdateSite.class);
    }

    private static Dependency require(String name) {
        return require(name, null);
    }

    private static Dependency require(String name, String version) {
        return new Dependency(name, version, false);
    }

    private static Dependency optional(String name) {
        return optional(name, null);
    }

    private static Dependency optional(String name, String version) {
        return new Dependency(name, version, true);
    }

    private static class Dependency {
        public final String name;
        public final VersionNumber version;
        public final boolean optional;

        private Dependency(String name, String version, boolean optional) {
            this.name = name;
            this.version = version == null ? null : new VersionNumber(version);
            this.optional = optional;
        }
    }
}
