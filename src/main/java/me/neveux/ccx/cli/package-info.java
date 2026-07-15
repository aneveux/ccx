/**
 * Picocli {@code @Command} classes for the ccx CLI.
 *
 * <p>Each top-level command is a CDI {@code @Dependent} bean that delegates
 * all business logic and file I/O to the service layer. Static nested subcommand
 * classes use field injection as an explicit CDI exception.
 */
package me.neveux.ccx.cli;
