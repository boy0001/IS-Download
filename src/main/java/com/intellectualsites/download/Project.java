package com.intellectualsites.download;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.incendo.jenkins.Jenkins;
import org.incendo.jenkins.objects.ArtifactDescription;
import org.incendo.jenkins.objects.BuildDescription;
import org.incendo.jenkins.objects.BuildInfo;
import org.incendo.jenkins.objects.JobInfo;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import xyz.kvantum.server.api.logging.Logger;
import xyz.kvantum.server.api.util.KvantumJsonFactory;
import xyz.kvantum.server.api.util.MapBuilder;

import java.util.*;
import java.util.regex.Pattern;

public class Project extends Node<Project.Target> {

    private final Jenkins jenkins;
    private final String identifier;
    private final Map<String, Target> targets;

    public Project(final String name, final JSONObject schema) {
        this.jenkins = Jenkins.newBuilder().withPath(schema.get("jenkins_base").toString()).build();
        this.identifier = name.toLowerCase();
        this.targets = new HashMap<>();
        if (!schema.containsKey("targets")) {
            throw new IllegalArgumentException("Schema does not contain target");
        }
        for (final Object targetObject : (JSONArray) schema.get("targets")) {
            final JSONObject targetJSON = (JSONObject) targetObject;
            final String targetIdentifier = targetJSON.get("identifier").toString();
            if (!targetJSON.containsKey("types")) {
                throw new IllegalArgumentException("Schema does not contain type");
            }
            final Map<String, Type> types = new HashMap<>();
            for (final Object typeObject : (JSONArray) targetJSON.get("types")) {
                final JSONObject typeJSON = (JSONObject) typeObject;
                final String typeIdentifier = typeJSON.get("identifier").toString();
                final String typeJobName = typeJSON.get("job_name").toString();
                if (!typeJSON.containsKey("versions")) {
                    throw new IllegalArgumentException("Schema does not contain versions");
                }
                final Map<String, VersionSchema> versionSchemas = new HashMap<>();
                for (final Object versionObject : (JSONArray) typeJSON.get("versions")) {
                    final JSONObject versionJSON = (JSONObject) versionObject;
                    final String versionIdentifier = versionJSON.get("identifier").toString();
                    final Pattern versionPattern = Pattern.compile(versionJSON.get("artifact_pattern").toString());
                    final VersionSchema versionSchema = new VersionSchema(versionIdentifier, versionPattern);
                    versionSchemas.put(versionIdentifier, versionSchema);
                }
                final Type type = new Type(typeIdentifier, typeJobName, versionSchemas);
                types.put(typeIdentifier, type);
            }
            final Target target = new Target(targetIdentifier, types);
            this.targets.put(targetIdentifier, target);
        }
        System.out.println("Loading builds");
        this.loadBuilds();
    }

    /**
     * Load, or reload builds
     */
    public void loadBuilds() {
        for (final Target target : this.targets.values()) {
            for (final Type type : target.types.values()) {
                try {
                    type.populateBuilds();
                } catch (final Throwable error) {
                    try {
                        Logger.error(
                            "Failed to populate builds for type {0} in target {1} for project {2}",
                            type.identifier == null ? "ty|null" : type.identifier,
                            target.identifier == null ? "ta|null" : target.identifier,
                            this.identifier == null ? "th|null" : this.identifier);
                    } catch (final NullPointerException exception) {
                        System.out.printf("Failed to populate builds for type %s in target %s for project %s",
                            type.identifier == null ? "ty|null" : type.identifier,
                            target.identifier == null ? "ta|null" : target.identifier,
                            this.identifier == null ? "th|null" : this.identifier);
                    }
                    error.printStackTrace();
                }
            }
        }
    }

    @Override public String getIdentifier() {
        return this.identifier;
    }

    @Override protected Target getChild(final String key) {
        return this.targets.get(key);
    }

    @Override public JSONObject generateJSON() {
        return KvantumJsonFactory.toJSONObject(
            MapBuilder.<String, Object>newHashMap().put("builds", this.targets.keySet()).get());
    }

    @RequiredArgsConstructor public final class Target extends Node<Type> {

        private final String identifier;
        private final Map<String, Type> types;

        @Override protected String getIdentifier() {
            return this.identifier;
        }

        @Override protected JSONObject generateJSON() {
            return KvantumJsonFactory.toJSONObject(
                MapBuilder.<String, Object>newHashMap().put("types", this.types.keySet()).get());
        }

        @Override protected Type getChild(String key) {
            return this.types.get(key);
        }
    }

    @RequiredArgsConstructor public final class Type extends Node<Type.Build> {

        private final String identifier;
        private final String jobName;
        private final Map<String, VersionSchema> versionSchemas;

        private final Map<String, Build> builds = new HashMap<>();
        private JobInfo jobInfo;

        /**
         * Repopulate the build list
         */
        public void populateBuilds() throws Throwable {
            System.out.println("Reading job info");
            this.jobInfo = jenkins.getJobInfo(this.jobName).get();
            this.builds.clear(); // Make sure it's emptied
            final int latest = this.jobInfo.getLastCompletedBuild().getNumber();
            System.out.println("Reading builds");

            List<BuildDescription> builds = new ArrayList<>(this.jobInfo.getBuilds());
            builds.sort(Comparator.comparing(BuildDescription::getNumber).reversed());
            if (builds.size() > 10) {
                builds = builds.subList(0, 10);
            }

            for (final BuildDescription buildDescription : builds) {
                boolean isLatest = buildDescription.getNumber() == latest;
                System.out.printf("Reading build info: %d\n", buildDescription.getNumber());
                final BuildInfo buildInfo = buildDescription.getBuildInfo().get();
                final Map<String, Version> versions = new HashMap<>();
                schemaLoop: for (final Map.Entry<String, VersionSchema> versionSchema : this.versionSchemas.entrySet()) {
                    for (final ArtifactDescription description : buildInfo.getArtifacts()) {
                        if (versionSchema.getValue().artifactPattern.matcher(description.getFileName()).matches()) {
                            final Version version = new Version(versionSchema.getKey(),
                                description.getFileName(), description.getUrl());
                            versions.put(versionSchema.getKey(), version);
                            break schemaLoop;
                        }
                    }
                }
                final Build build = new Build(Integer.toString(buildInfo.getId()), versions);
                this.builds.put(build.identifier, build);
                if (isLatest) {
                    this.builds.put("latest", build);
                }
            }
        }

        @Override protected String getIdentifier() {
            return this.identifier;
        }

        @Override protected JSONObject generateJSON() {
            return KvantumJsonFactory.toJSONObject(
                MapBuilder.<String, Object>newHashMap().put("builds", this.builds.keySet()).get());
        }

        @Override protected Build getChild(String key) {
            return builds.get(key);
        }

        @RequiredArgsConstructor public final class Build extends Node<Version> {
            private final String identifier;
            private final Map<String, Version> versions;

            @Override protected String getIdentifier() {
                return this.identifier;
            }

            @Override protected JSONObject generateJSON() {
                return KvantumJsonFactory.toJSONObject(
                    MapBuilder.<String, Object>newHashMap().put("versions", this.versions.keySet()).get());
            }

            @Override protected Version getChild(final String key) {
                return versions.get(key);
            }
        }

        @RequiredArgsConstructor public final class Version extends Node<Void> {
            private final String identifier;
            private final String fileName;
            @Getter private final String downloadUrl;

            @Override protected String getIdentifier() {
                return this.identifier;
            }

            @Override protected JSONObject generateJSON() {
                return KvantumJsonFactory.toJSONObject(
                    MapBuilder.<String, Object>newHashMap().put("fileName", this.fileName)
                        .put("download", this.downloadUrl).get());
            }

            @Override protected Void getChild(final String key) {
                throw new UnsupportedOperationException("Cannot generate version child");
            }
        }
    }

    @RequiredArgsConstructor public final class VersionSchema {
        private final String identifier;
        private final Pattern artifactPattern;
    }

}