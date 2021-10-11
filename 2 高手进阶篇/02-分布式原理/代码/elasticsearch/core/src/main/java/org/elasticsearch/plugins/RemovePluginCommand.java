/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.plugins;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.env.Environment;

import static org.elasticsearch.cli.Terminal.Verbosity.VERBOSE;

/**
 * A command for the plugin CLI to remove a plugin from Elasticsearch.
 */
class RemovePluginCommand extends EnvironmentAwareCommand {

    private final OptionSpec<String> arguments;

    RemovePluginCommand() {
        super("removes a plugin from Elasticsearch");
        this.arguments = parser.nonOptions("plugin name");
    }

    @Override
    protected void execute(final Terminal terminal, final OptionSet options, final Environment env)
            throws Exception {
        final String pluginName = arguments.value(options);
        execute(terminal, pluginName, env);
    }

    /**
     * Remove the plugin specified by {@code pluginName}.
     *
     * @param terminal   the terminal to use for input/output
     * @param pluginName the name of the plugin to remove
     * @param env        the environment for the local node
     * @throws IOException   if any I/O exception occurs while performing a file operation
     * @throws UserException if plugin name is null
     * @throws UserException if plugin directory does not exist
     * @throws UserException if the plugin bin directory is not a directory
     */
    void execute(final Terminal terminal, final String pluginName, final Environment env)
            throws IOException, UserException {
        if (pluginName == null) {
            throw new UserException(ExitCodes.USAGE, "plugin name is required");
        }

        terminal.println("-> removing [" + Strings.coalesceToEmpty(pluginName) + "]...");

        final Path pluginDir = env.pluginsFile().resolve(pluginName);
        if (Files.exists(pluginDir) == false) {
            final String message = String.format(
                    Locale.ROOT,
                    "plugin [%s] not found; "
                            + "run 'elasticsearch-plugin list' to get list of installed plugins",
                    pluginName);
            throw new UserException(ExitCodes.CONFIG, message);
        }

        final List<Path> pluginPaths = new ArrayList<>();

        final Path pluginBinDir = env.binFile().resolve(pluginName);
        if (Files.exists(pluginBinDir)) {
            if (Files.isDirectory(pluginBinDir) == false) {
                throw new UserException(
                        ExitCodes.IO_ERROR, "bin dir for " + pluginName + " is not a directory");
            }
            pluginPaths.add(pluginBinDir);
            terminal.println(VERBOSE, "removing [" + pluginBinDir + "]");
        }

        terminal.println(VERBOSE, "removing [" + pluginDir + "]");
        final Path tmpPluginDir = env.pluginsFile().resolve(".removing-" + pluginName);
        try {
            Files.move(pluginDir, tmpPluginDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            /*
             * On a union file system if the plugin that we are removing is not installed on the
             * top layer then atomic move will not be supported. In this case, we fall back to a
             * non-atomic move.
             */
            Files.move(pluginDir, tmpPluginDir);
        }
        pluginPaths.add(tmpPluginDir);

        IOUtils.rm(pluginPaths.toArray(new Path[pluginPaths.size()]));

        /*
         * We preserve the config files in case the user is upgrading the plugin, but we print a
         * message so the user knows in case they want to remove manually.
         */
        final Path pluginConfigDir = env.configFile().resolve(pluginName);
        if (Files.exists(pluginConfigDir)) {
            final String message = String.format(
                    Locale.ROOT,
                    "-> preserving plugin config files [%s] in case of upgrade; "
                            + "delete manually if not needed",
                    pluginConfigDir);
            terminal.println(message);
        }
    }

}
