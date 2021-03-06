/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.plugin.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugins.annotations.Parameter;
import org.jboss.as.cli.CliInitializationException;
import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandContextFactory;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.cli.batch.Batch;
import org.jboss.as.cli.batch.BatchManager;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.plugin.common.ServerOperations;
import org.jboss.as.plugin.common.Streams;
import org.jboss.dmr.ModelNode;

/**
 * CLI commands to run.
 * <p/>
 * <pre>
 *      &lt;commands&gt;
 *          &lt;batch&gt;false&lt;/batch&gt;
 *          &lt;command&gt;/subsystem=logging/console-handler:CONSOLE:write-attribute(name=level,value=TRACE)&lt;/command&gt;
 *      &lt;/commands&gt;
 * </pre>
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 * @author <a href="mailto:heinz.wilming@akquinet.de">Heinz Wilming</a>
 */
public class Commands {

    /**
     * {@code true} if commands should be executed in a batch or {@code false} if they should be executed one at a
     * time.
     */
    @Parameter
    private boolean batch;

    /**
     * The CLI commands to execute.
     */
    @Parameter
    private List<String> commands = new ArrayList<String>();

    /**
     * The CLI script files to execute.
     */
    @Parameter
    private List<File> scripts = new ArrayList<File>();

    /**
     * Indicates whether or not commands should be executed in a batch.
     *
     * @return {@code true} if commands should be executed in a batch, otherwise
     *         {@code false}
     */
    public boolean isBatch() {
        return batch;
    }

    /**
     * Checks of there are commands that should be executed.
     *
     * @return {@code true} if there are commands to be processed, otherwise {@code false}
     */
    public boolean hasCommands() {
        return commands != null && !commands.isEmpty();
    }

    /**
     * Checks of there are a CLI script file that should be executed.
     *
     * @return {@code true} if there are a CLI script to be processed, otherwise
     *         {@code false}
     */
    public boolean hasScripts() {
        return scripts != null && !scripts.isEmpty();
    }

    /**
     * Execute the commands.
     *
     * @param client the client used to execute the commands
     *
     * @throws IOException              if the client has an IOException
     * @throws IllegalArgumentException if an command is invalid
     */
    public final void execute(final ModelControllerClient client) throws IOException {
        final boolean hasCommands = hasCommands();
        final boolean hasScripts = hasScripts();

        if (hasCommands || hasScripts) {
            final CommandContext ctx = create(client);
            // JBASMP-48 Exception ModelControllerClient is closed
            //try {

                if (isBatch()) {
                    executeBatch(ctx);
                } else {
                    executeCommands(ctx);
                }
                executeScripts(ctx);
            // JBASMP-48 Exception ModelControllerClient is closed
            //} finally {
            //  ctx.terminateSession();
            //  ctx.bindClient(null);
            //}
        }

    }

    private void executeScripts(final CommandContext ctx) throws IOException {

        for (File script : scripts) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(script), "UTF-8"));
                String line = reader.readLine();
                while (!ctx.isTerminated() && line != null) {

                    try {
                        ctx.handle(line.trim());
                    } catch (CommandFormatException e) {
                        throw new IllegalArgumentException(String.format("Command '%s' is invalid. %s", line, e.getLocalizedMessage()), e);
                    } catch (CommandLineException e) {
                        throw new IllegalArgumentException(String.format("Command execution failed for command '%s'. %s", line, e.getLocalizedMessage()), e);
                    }
                    line = reader.readLine();

                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to process file '" + script.getAbsolutePath() + "'", e);
            } finally {
                Streams.safeClose(reader);
            }
        }
    }

    private void executeCommands(final CommandContext ctx) throws IOException {
        for (String cmd : commands) {
            try {
                ctx.handle(cmd);
            } catch (CommandFormatException e) {
                throw new IllegalArgumentException(String.format("Command '%s' is invalid. %s", cmd, e.getLocalizedMessage()), e);
            } catch (CommandLineException e) {
                throw new IllegalArgumentException(String.format("Command execution failed for command '%s'. %s", cmd, e.getLocalizedMessage()), e);
            }
        }
    }

    private void executeBatch(final CommandContext ctx) throws IOException {
        final BatchManager batchManager = ctx.getBatchManager();
        if (batchManager.activateNewBatch()) {
            final Batch batch = batchManager.getActiveBatch();
            for (String cmd : commands) {
                try {
                    batch.add(ctx.toBatchedCommand(cmd));
                } catch (CommandFormatException e) {
                    throw new IllegalArgumentException(String.format("Command '%s' is invalid. %s", cmd, e.getLocalizedMessage()), e);
                }
            }
            final ModelNode result = ctx.getModelControllerClient().execute(batch.toRequest());
            if (!ServerOperations.isSuccessfulOutcome(result)) {
                throw new IllegalArgumentException(ServerOperations.getFailureDescriptionAsString(result));
            }
        }
    }

    /**
     * Creates the command context and binds the client to the context.
     * <p/>
     * If the client is {@code null}, no client is bound to the context.
     *
     * @param client current connected client
     *
     * @return the command line context
     *
     * @throws IllegalStateException if the context fails to initialize
     */
    public static CommandContext create(final ModelControllerClient client) {
        final CommandContext commandContext;
        try {
            commandContext = CommandContextFactory.getInstance().newCommandContext();
            commandContext.bindClient(client);
        } catch (CliInitializationException e) {
            throw new IllegalStateException("Failed to initialize CLI context", e);
        }
        return commandContext;
    }
}
