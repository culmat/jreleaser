/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2020-2023 The JReleaser authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jreleaser.packagers;

import org.jreleaser.bundle.RB;
import org.jreleaser.model.internal.JReleaserContext;
import org.jreleaser.model.internal.distributions.Distribution;
import org.jreleaser.model.internal.packagers.ChocolateyPackager;
import org.jreleaser.model.internal.release.BaseReleaser;
import org.jreleaser.model.internal.release.GithubReleaser;
import org.jreleaser.model.internal.release.Releaser;
import org.jreleaser.model.spi.packagers.PackagerProcessingException;
import org.jreleaser.mustache.TemplateContext;
import org.jreleaser.sdk.command.Command;
import org.jreleaser.util.PlatformUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_BUCKET_REPO_CLONE_URL;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_BUCKET_REPO_URL;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_ICON_URL;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_PACKAGE_NAME;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_PACKAGE_SOURCE_URL;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_PACKAGE_VERSION;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_REPOSITORY_CLONE_URL;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_REPOSITORY_URL;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_SOURCE;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_TITLE;
import static org.jreleaser.model.Constants.KEY_CHOCOLATEY_USERNAME;
import static org.jreleaser.model.Constants.KEY_DISTRIBUTION_JAVA_MAIN_CLASS;
import static org.jreleaser.model.Constants.KEY_DISTRIBUTION_JAVA_MAIN_MODULE;
import static org.jreleaser.model.Constants.KEY_DISTRIBUTION_PACKAGE_DIRECTORY;
import static org.jreleaser.model.Constants.KEY_PROJECT_LICENSE_URL;
import static org.jreleaser.mustache.Templates.resolveTemplate;
import static org.jreleaser.templates.TemplateUtils.trimTplExtension;
import static org.jreleaser.util.FileUtils.listFilesAndProcess;
import static org.jreleaser.util.StringUtils.isBlank;

/**
 * @author Andres Almiray
 * @since 0.1.0
 */
public class ChocolateyPackagerProcessor extends AbstractRepositoryPackagerProcessor<ChocolateyPackager> {
    public ChocolateyPackagerProcessor(JReleaserContext context) {
        super(context);
    }

    @Override
    protected void doPackageDistribution(Distribution distribution, TemplateContext props, Path packageDirectory) throws PackagerProcessingException {
        super.doPackageDistribution(distribution, props, packageDirectory);

        copyPreparedFiles(props);

        if (packager.isRemoteBuild()) {
            return;
        }

        if (!PlatformUtils.isWindows()) {
            context.getLogger().warn(RB.$("ERROR_packager_requires_platform", "Windows"));
            return;
        }

        createChocolateyPackage(distribution, props);
    }

    @Override
    protected void doPublishDistribution(Distribution distribution, TemplateContext props) throws PackagerProcessingException {
        if (packager.isRemoteBuild()) {
            super.doPublishDistribution(distribution, props);
            return;
        }

        if (context.isDryrun()) {
            context.getLogger().error(RB.$("dryrun.set"));
            return;
        }

        if (!PlatformUtils.isWindows()) {
            context.getLogger().warn(RB.$("ERROR_packager_requires_platform", "Windows"));
            return;
        }

        publishChocolateyPackage(distribution, props);
    }

    @Override
    protected void fillPackagerProperties(TemplateContext props, Distribution distribution) {
        props.set(KEY_DISTRIBUTION_JAVA_MAIN_CLASS, distribution.getJava().getMainClass());
        props.set(KEY_DISTRIBUTION_JAVA_MAIN_MODULE, distribution.getJava().getMainModule());
        BaseReleaser<?, ?> releaser = context.getModel().getRelease().getReleaser();

        if (!props.contains(KEY_PROJECT_LICENSE_URL) ||
            isBlank(props.get(KEY_PROJECT_LICENSE_URL))) {
            context.getLogger().warn(RB.$("ERROR_project_no_license_url"));
        }

        String releaseUrl = releaser.getResolvedRepoUrl(context.getModel());
        String repositoryUrl = releaser.getResolvedRepoUrl(context.getModel(), packager.getRepository().getOwner(), packager.getRepository().getResolvedName());

        props.set(KEY_CHOCOLATEY_BUCKET_REPO_URL, repositoryUrl);
        props.set(KEY_CHOCOLATEY_BUCKET_REPO_CLONE_URL,
            releaser.getResolvedRepoCloneUrl(context.getModel(), packager.getRepository().getOwner(), packager.getRepository().getResolvedName()));
        props.set(KEY_CHOCOLATEY_PACKAGE_SOURCE_URL, packager.isRemoteBuild() ? repositoryUrl : releaseUrl);
        props.set(KEY_CHOCOLATEY_REPOSITORY_URL, repositoryUrl);
        props.set(KEY_CHOCOLATEY_REPOSITORY_CLONE_URL,
            releaser.getResolvedRepoCloneUrl(context.getModel(), packager.getRepository().getOwner(), packager.getRepository().getResolvedName()));

        props.set(KEY_CHOCOLATEY_PACKAGE_NAME, packager.getPackageName());
        props.set(KEY_CHOCOLATEY_PACKAGE_VERSION, resolveTemplate(packager.getPackageVersion(), props));
        props.set(KEY_CHOCOLATEY_USERNAME, packager.getUsername());
        props.set(KEY_CHOCOLATEY_TITLE, packager.getTitle());
        props.set(KEY_CHOCOLATEY_ICON_URL, resolveTemplate(packager.getIconUrl(), props));
        props.set(KEY_CHOCOLATEY_SOURCE, packager.getSource());
    }

    @Override
    protected void writeFile(Distribution distribution,
                             String content,
                             TemplateContext props,
                             Path outputDirectory,
                             String fileName) throws PackagerProcessingException {
        Releaser<?> gitService = context.getModel().getRelease().getReleaser();
        if (fileName.contains(".github") && (!packager.isRemoteBuild() || !(gitService instanceof GithubReleaser))) {
            // skip
            return;
        }

        fileName = trimTplExtension(fileName);

        Path outputFile = "binary.nuspec".equals(fileName) ?
            outputDirectory.resolve(distribution.getName()).resolve(packager.getPackageName().concat(".nuspec")) :
            fileName.endsWith(".ps1") ? outputDirectory.resolve(distribution.getName()).resolve(fileName) :
                outputDirectory.resolve(fileName);

        writeFile(content, outputFile);
    }

    private void createChocolateyPackage(Distribution distribution, TemplateContext props) throws PackagerProcessingException {
        Path packageDirectory = props.get(KEY_DISTRIBUTION_PACKAGE_DIRECTORY);
        Path execDirectory = packageDirectory.resolve(distribution.getName());

        Command cmd = new Command("choco")
            .arg("pack")
            .arg(packager.getPackageName() + ".nuspec");

        context.getLogger().debug(String.join(" ", cmd.getArgs()));
        executeCommand(execDirectory, cmd);
    }

    private void publishChocolateyPackage(Distribution distribution, TemplateContext props) throws PackagerProcessingException {
        Path packageDirectory = props.get(KEY_DISTRIBUTION_PACKAGE_DIRECTORY);
        Path execDirectory = packageDirectory.resolve(distribution.getName());

        Command cmd = new Command("choco")
            .arg("apikey")
            .arg("-k")
            .arg(packager.getApiKey())
            .arg("-source")
            .arg(packager.getSource());
        executeCommand(execDirectory, cmd);

        try {
            Optional<String> nuget = listFilesAndProcess(execDirectory, files ->
                files.map(Path::getFileName)
                    .map(Path::toString)
                    .filter(s -> s.endsWith(".nupkg"))
                    .findFirst().orElse(null));

            if (nuget.isPresent()) {
                cmd = new Command("choco")
                    .arg("push")
                    .arg(nuget.get())
                    .arg("-s")
                    .arg(packager.getSource());

                context.getLogger().debug(String.join(" ", cmd.getArgs()));
                executeCommand(execDirectory, cmd);
            } else {
                throw new PackagerProcessingException(RB.$("ERROR_chocolatey_nuget_not_found",
                    context.relativizeToBasedir(execDirectory)));
            }
        } catch (IOException e) {
            throw new PackagerProcessingException(RB.$("ERROR_unexpected_error"), e);
        }
    }
}
