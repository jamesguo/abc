/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.abcft.pdfextract.core.util;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exposes PDFExtract version.
 */
public final class Version
{
    private static final String PDFEXTRACT_VERSION_PROPERTIES =
            "com/abcft/pdfextract/resources/version.properties";

    private static final Pattern BUILD_TAG_RE = Pattern.compile("v(?<major>\\d+)\\.(?<minor>\\d+)(?:\\.(?<build>\\d+))?(?:-(?<suffix>[-\\w]+))?");
    private static final Pattern BUILD_UNTAGED_RE = Pattern.compile("-\\d+-g[\\da-fA-F]+$");

    private static boolean propLoaded;

    private static String version;
    private static int versionMajor;
    private static int versionMinor;
    private static int versionBuild;
    private static String versionSuffix;

    private static String buildKey;
    private static String buildVersion;
    private static String buildRevision;
    private static String buildBranch;
    private static String buildTags;
    private static String buildDescribe;
    private static String buildTime;
    private static String buildHost;
    private static String buildUser;
    private static boolean isRelease;
    private static boolean isDirty;


    private static void loadBuildProps() {
        if (propLoaded) {
            return;
        }
        try {
            URL url = Version.class.getClassLoader().getResource(PDFEXTRACT_VERSION_PROPERTIES);
            if (url == null) {
                propLoaded = true;
                return;
            }
            Properties properties = new Properties();
            properties.load(url.openStream());

            version = properties.getProperty("pdfextract.version");

            buildRevision = properties.getProperty("pdfextract.git.revision");
            buildBranch = properties.getProperty("pdfextract.git.branch");
            buildDescribe = properties.getProperty("pdfextract.git.describe");
            buildTags = properties.getProperty("pdfextract.git.tags");
            buildTime = properties.getProperty("pdfextract.build.time");
            buildHost = properties.getProperty("pdfextract.build.host");
            buildUser = properties.getProperty("pdfextract.build.user");

            isDirty = BooleanUtils.toBoolean(properties.getProperty("pdfextract.git.dirty"));

            VersionInfo dirtyVersion = parseTagVersion(buildDescribe);
            if (isDirty || null == dirtyVersion) {
                isRelease = false;
            } else {
                isRelease = StringUtils.isEmpty(dirtyVersion.suffix)
                        || !BUILD_UNTAGED_RE.matcher(dirtyVersion.suffix).find();
            }

            // 优先检测 Tags
            if (!StringUtils.isEmpty(buildTags)) {
                String[] tags = buildTags.split(",");
                boolean versionParsed = false;
                for (String tag : tags) {
                    VersionInfo versionInfo = parseTagVersion(tag);
                    if (null == versionInfo) {
                        continue;
                    }
                    if (!versionParsed) {
                        versionMajor = versionInfo.major;
                        versionMinor = versionInfo.minor;
                        versionBuild = versionInfo.build;
                        versionParsed = true;
                    }
                    String suffixText = versionInfo.suffix;
                    if (null == versionSuffix) {
                        versionSuffix = suffixText == null ? "" : suffixText;
                    } else if (null == suffixText) {
                        versionSuffix = "";
                    }
                }
            } else {
                parseTagVersion(buildDescribe);
            }
            buildVersion = String.format("v%d.%d.%d", versionMajor, versionMinor, versionBuild);
            buildKey = String.format("v%d.%d", versionMajor, versionMinor);

            propLoaded = true;
        }
        catch (IOException io)
        {
            propLoaded = false;
        }

    }

    private static final class VersionInfo {
        final int major;
        final int minor;
        final int build;

        final String suffix;
        final String text;

        VersionInfo(String text, Matcher m) {
            this.text = text;
            major = Integer.parseInt(m.group("major"));
            minor = Integer.parseInt(m.group("minor"));
            String buildText = m.group("build");
            if (!StringUtils.isEmpty(buildText)) {
                build = Integer.parseInt(buildText);
            } else {
                build = 0;
            }
            this.suffix = m.group("suffix");
        }
    }

    private static VersionInfo parseTagVersion(String tag) {
        if (StringUtils.isEmpty(tag)) {
            return null;
        }
        Matcher m = BUILD_TAG_RE.matcher(tag);
        if (!m.matches()) {
            return null;
        }
        return new VersionInfo(tag, m);
    }

    private Version()
    {
        // static helper
    }

    /**
     * Returns the build revision.
     */
    public static String getBuildKey()
    {
        loadBuildProps();
        return buildKey;
    }

    /**
     * Returns the version of PDFExtract.
     */
    public static String getVersion() {
        loadBuildProps();
        return version;
    }

    public static int getVersionMajor() {
        loadBuildProps();
        return versionMajor;
    }

    public static int getVersionMinor() {
        loadBuildProps();
        return versionMinor;
    }

    public static int getVersionBuild() {
        loadBuildProps();
        return versionBuild;
    }

    public static String getBuildVersion() {
        loadBuildProps();
        return buildVersion;
    }

    public static boolean isSameReleaseVersion(String version) {
        VersionInfo versionInfo = parseTagVersion(version);
        if (null == versionInfo) {
            return false;
        }
        return versionInfo.major == versionMajor
                && versionInfo.minor == versionMinor;
    }

    /**
     * Returns the build revision.
     */
    public static String getBuildRevision() {
        loadBuildProps();
        return buildRevision;
    }

    /**
     * Returns the build branch.
     */
    public static String getBuildBranch() {
        loadBuildProps();
        return buildBranch;
    }

    /**
     * Returns the build tags.
     */
    public static String getBuildTags() {
        loadBuildProps();
        return buildTags;
    }

    /**
     * Returns the build describe.
     */
    public static String getBuildDescribe() {
        loadBuildProps();
        return buildDescribe;
    }

    /**
     * Returns the build suffix.
     */
    public static String getVersionSuffix() {
        return versionSuffix;
    }

    /**
     * Returns the build time.
     */
    public static String getBuildTime() {
        loadBuildProps();
        return buildTime;
    }

    /**
     * Returns the build host.
     */
    public static String getBuildHost() {
        loadBuildProps();
        return buildHost;
    }

    /**
     * Returns the build user.
     */
    public static String getBuildUser() {
        loadBuildProps();
        return buildUser;
    }

    /**
     * Determine whether this application is built from release branch.
     */
    public static boolean isRelease() {
        loadBuildProps();
        return isRelease;
    }

    /**
     * Determine whether this application is built from a workspace with uncommitted local changes.
     */
    public static boolean isDirty() {
        loadBuildProps();
        return isDirty;
    }

}
