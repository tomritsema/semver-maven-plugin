package org.apache.maven.plugins.semver.providers;

import org.apache.maven.plugins.semver.SemverMavenPlugin;
import org.apache.maven.plugins.semver.exceptions.SemverException;
import org.apache.maven.plugins.semver.goals.SemverGoals;
import org.apache.maven.plugins.semver.runmodes.RunMode;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


@Component(role = VersionProvider.class)
public class VersionProviderImpl implements VersionProvider {

  @Requirement
  private Logger LOG;
  @Requirement
  private RepositoryProvider repositoryProvider;

  /**
   * <p>In the constructor the logging and the configuration is given.</p>
   * <p>These are elements which continue to return in the different methods.</p>
   */
  @Inject
  public VersionProviderImpl() {
  }

  /**
   * <p>Determine raw version list from POM-version.</p>
   *
   * @param pomVersion example: 0.x.x-SNAPSHOT
   * @return list of development, git and release-version
   * @throws SemverException native exception
   * @throws IOException write to disk exception
   * @throws GitAPIException repository exception
   */
  public Map<VersionProvider.RAW_VERSION, String> determineRawVersions(SemverGoals.SEMVER_GOAL semverGoal, RunMode.RUNMODE runMode, String configBranchVersion, String configMetaData, String pomVersion) throws SemverException, IOException, GitAPIException {

    Map<VersionProvider.RAW_VERSION, String> versions = new HashMap<>();

    int majorVersion;
    int minorVersion;
    int patchVersion;

    String[] rawVersion = pomVersion.split("\\.");
    if (rawVersion.length > 0 && rawVersion.length == 3) {
      LOG.debug("Set version-variables from POM.xml");
      LOG.debug(SemverMavenPlugin.MOJO_LINE_BREAK);
      majorVersion = Integer.valueOf(rawVersion[0]);
      minorVersion = Integer.valueOf(rawVersion[1]);
      patchVersion = Integer.valueOf(rawVersion[2].substring(0, rawVersion[2].lastIndexOf('-')));
    } else {
      LOG.error("Unrecognized version-pattern");
      LOG.error("Semver plugin is terminating");
      throw new SemverException("Unrecognized version-pattern", "Could not parse version from POM.xml because of not parseble version-pattern");
    }

    LOG.debug("MAJOR-version                     : [ {} ]", majorVersion);
    LOG.debug("MINOR-version                     : [ {} ]", minorVersion);
    LOG.debug("PATCH-version                     : [ {} ]", patchVersion);
    LOG.debug(SemverMavenPlugin.MOJO_LINE_BREAK);

    if(semverGoal == SemverGoals.SEMVER_GOAL.MAJOR) {
      majorVersion = majorVersion + 1;
      minorVersion = 0;
      patchVersion = 0;
    } else if(semverGoal == SemverGoals.SEMVER_GOAL.MINOR) {
      minorVersion = minorVersion + 1;
      patchVersion = 0;
    } else if(semverGoal == SemverGoals.SEMVER_GOAL.PATCH) {
      patchVersion = patchVersion + 1;
    }

    String developmentVersion = majorVersion + "." + minorVersion + "." + patchVersion + "-SNAPSHOT";


    //TODO:SH move this part to a RunModeNative and RunModeNativeRpm implementation
    String releaseVersion;
    String scmVersion;
    if(runMode.equals(RunMode.RUNMODE.NATIVE_BRANCH) || runMode.equals(RunMode.RUNMODE.NATIVE_BRANCH_RPM)) {
      scmVersion = determineReleaseBranchTag(runMode, configBranchVersion, patchVersion, minorVersion, majorVersion);
      releaseVersion = scmVersion;
    } else {
      scmVersion = determineReleaseTag(runMode, patchVersion, minorVersion, majorVersion);
      releaseVersion = majorVersion + "." + minorVersion + "." + patchVersion;
    }

    String metaData = determineBuildMetaData(runMode, configMetaData, patchVersion, minorVersion, majorVersion);

    LOG.info("New DEVELOPMENT-version            : [ {} ]", developmentVersion);
    LOG.info("New GIT-version                    : [ {}{} ]", scmVersion, metaData);
    LOG.info("New RELEASE-version                : [ {} ]", releaseVersion);
    LOG.info(SemverMavenPlugin.FUNCTION_LINE_BREAK);

    versions.put(VersionProvider.RAW_VERSION.DEVELOPMENT, developmentVersion);
    versions.put(VersionProvider.RAW_VERSION.RELEASE, releaseVersion);
    versions.put(VersionProvider.RAW_VERSION.SCM, scmVersion+metaData);
    versions.put(VersionProvider.RAW_VERSION.MAJOR, String.valueOf(majorVersion));
    versions.put(VersionProvider.RAW_VERSION.MINOR, String.valueOf(minorVersion));
    versions.put(VersionProvider.RAW_VERSION.PATCH, String.valueOf(patchVersion));

    repositoryProvider.isLocalVersionCorrupt(scmVersion);
    return versions;
  }

  /**
   * <p>Determine release versions from {@link RAW_VERSION}.</p>
   *
   * @param rawVersions raw version map with development version patch, minor and major the {@link org.apache.maven.plugins.semver.providers.VersionProvider.RAW_VERSION} enumeration is used to define the map
   * @return finalVersions
   */
  @Override
  public Map<FINAL_VERSION, String> determineReleaseVersions(Map<RAW_VERSION, String> rawVersions) {
    Map<FINAL_VERSION, String> finalVersions = new HashMap<>();
    finalVersions.put(FINAL_VERSION.DEVELOPMENT, rawVersions.get(RAW_VERSION.DEVELOPMENT));
    finalVersions.put(FINAL_VERSION.RELEASE, rawVersions.get(RAW_VERSION.RELEASE));
    finalVersions.put(FINAL_VERSION.SCM, rawVersions.get(RAW_VERSION.RELEASE));
    return finalVersions;
  }

  /**
   * <p>Determine release-versions from {@link VersionProvider.RAW_VERSION}.</p>
   * <p>This version contains also the buildmeta-data and branch information.</p>
   *
   * @param rawVersions raw version map with development version patch, minor and major the {@link org.apache.maven.plugins.semver.providers.VersionProvider.RAW_VERSION} enumeration is used to define the map
   * @return finalVersions
   */
  @Override
  public Map<FINAL_VERSION, String> determineReleaseBranchVersions(Map<VersionProvider.RAW_VERSION, String> rawVersions, RunMode.RUNMODE runMode, String metaData, String branchVersion) {

    int patch = Integer.parseInt(rawVersions.get(VersionProvider.RAW_VERSION.PATCH));
    int minor = Integer.parseInt(rawVersions.get(VersionProvider.RAW_VERSION.MINOR));
    int major = Integer.parseInt(rawVersions.get(VersionProvider.RAW_VERSION.MAJOR));

    String releaseTag = determineReleaseTag(runMode, patch, minor, major);
    String buildMetaData = determineBuildMetaData(runMode, metaData, patch, minor, major);

    StringBuilder releaseVersion = new StringBuilder();
    if (branchVersion != null && !branchVersion.isEmpty()) {
      releaseVersion.append(branchVersion);
      releaseVersion.append("-");
    }
    releaseVersion.append(releaseTag);

    StringBuilder scmVersion = new StringBuilder();
    scmVersion.append(releaseVersion);
    scmVersion.append(buildMetaData);

    if (LOG != null) {
      LOG.info("New DEVELOPMENT-version            : [ {} ]", rawVersions.get(VersionProvider.RAW_VERSION.DEVELOPMENT));
      LOG.info("New BRANCH GIT build metadata      : [ {} ]", buildMetaData);
      LOG.info("New BRANCH GIT-version             : [ {} ]", scmVersion);
      LOG.info("New BRANCH RELEASE-version         : [ {} ]", releaseVersion);
      LOG.info(SemverMavenPlugin.MOJO_LINE_BREAK);
    }
    Map<FINAL_VERSION, String> finalVersions = new HashMap<>();
    finalVersions.put(FINAL_VERSION.DEVELOPMENT, rawVersions.get(VersionProvider.RAW_VERSION.DEVELOPMENT));
    finalVersions.put(FINAL_VERSION.BUILD_METADATA, buildMetaData);
    finalVersions.put(FINAL_VERSION.SCM, scmVersion.toString());
    finalVersions.put(FINAL_VERSION.RELEASE, releaseVersion.toString());

    return finalVersions;
  }

  /**
   * <p>Determine general release-tag.</p>
   * <p>Examples:</p>
   * <ul><b>NORMAL-release</b>
   * <li>1.1.1</li>
   * </ul>
   * <ul><b>BRANCH-release</b>
   * <li>1.1.1-001001001</li>
   * </ul>
   *
   * @param patch patch is the number to define a bugfix in symantic-versioning
   * @param minor minor is the number to define a feature in symantic-versioning
   * @param major major is the number to define a breaking change in symantic-versioning
   * @return release tag
   */
  @Override
  public String determineReleaseBranchTag(RunMode.RUNMODE runMode, String branchVersion, int patch, int minor, int major) {
    StringBuilder releaseTag = new StringBuilder();
    releaseTag.append(major);
    releaseTag.append(".");
    releaseTag.append(minor);
    releaseTag.append(".");
    releaseTag.append(patch);
    if (runMode == RunMode.RUNMODE.RELEASE_BRANCH_RPM || runMode == RunMode.RUNMODE.NATIVE_BRANCH_RPM) {
      releaseTag = new StringBuilder();
      releaseTag.append(branchVersion);
      releaseTag.append(String.format("%03d%03d%03d", major, minor, patch));
    }
    return releaseTag.toString();
  }

  /**
   * <p>Determine general release-tag.</p>
   * <p>Examples:</p>
   * <ul><b>NORMAL-release</b>
   * <li>1.1.1</li>
   * </ul>
   * <ul><b>BRANCH-release</b>
   * <li>1.1.1-001001001</li>
   * </ul>
   *
   * @param patch patch is the number to define a bugfix in symantic-versioning
   * @param minor minor is the number to define a feature in symantic-versioning
   * @param major major is the number to define a breaking change in symantic-versioning
   * @return release tag
   */
  @Override
  public String determineReleaseTag(RunMode.RUNMODE runMode, int patch, int minor, int major) {
    StringBuilder releaseTag = new StringBuilder();
    releaseTag.append(major);
    releaseTag.append(".");
    releaseTag.append(minor);
    releaseTag.append(".");
    releaseTag.append(patch);
    if (runMode == RunMode.RUNMODE.RELEASE_BRANCH_RPM || runMode == RunMode.RUNMODE.NATIVE_BRANCH_RPM) {
      releaseTag = new StringBuilder();
      releaseTag.append(String.format("%03d%03d%03d", major, minor, patch));
    }
    return releaseTag.toString();
  }

  /**
   * <p>Determine wether or not buildMetaData had to be added to the scmversion for GIT</p>
   *
   * @param patch patch is the number to define a bugfix in symantic-versioning
   * @param minor minor is the number to define a feature in symantic-versioning
   * @param major major is the number to define a breaking change in symantic-versioning
   * @return build metadata
   */
  @Override
  public String determineBuildMetaData(RunMode.RUNMODE runmode, String metaData, int patch, int minor, int major) {
    StringBuilder buildMetaData = new StringBuilder();
    if (runmode == RunMode.RUNMODE.RELEASE_BRANCH_RPM ||
            runmode == RunMode.RUNMODE.NATIVE_BRANCH_RPM) {
      String buildMetaDataBranch = major + "." + minor + "." + patch;
      buildMetaData.append("+");
      buildMetaData.append(buildMetaDataBranch);
    }
    if (!metaData.isEmpty()) {
      buildMetaData.append("+");
      buildMetaData.append(metaData);
    }
    return buildMetaData.toString();
  }

  /**
   * <p>Determine if the version in the pom.xml is corrupt.</p>
   * <p>If this is the case then exit the semver-plugin.</p>
   *
   * @param pomVersion get pom version from project
   * @return is version corrupt?
   * @throws SemverException native semver exception
   */
  @Override
  public boolean isVersionCorrupt(String pomVersion) throws SemverException {
    boolean isVersionCorrupt = false;
    LOG.info("Check on pom-version");
    LOG.info(SemverMavenPlugin.MOJO_LINE_BREAK);
    if (pomVersion == null || pomVersion.isEmpty()) {
      isVersionCorrupt = true;
      LOG.error("");
      LOG.error("The version in the pom.xml is NULL of empty please correct the pom.xml");
      LOG.error("");
    } else if (!pomVersion.contains("-SNAPSHOT")) {
      isVersionCorrupt = true;
      LOG.error("");
      LOG.error("The version in the pom.xml does not contain -SNAPSHOT. Please repair the version-string");
      LOG.error("");
    } else {
      LOG.info("Pom-version is correct             : [ {} ]", pomVersion);
    }
    LOG.info(SemverMavenPlugin.FUNCTION_LINE_BREAK);
    return isVersionCorrupt;
  }


}
