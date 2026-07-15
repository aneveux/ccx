/**
 * CDI {@code @ApplicationScoped} service layer — all business logic and file I/O.
 *
 * <p>Services are the only classes that read and write files. All writes are atomic:
 * each write targets a {@code .tmp} sibling which is then moved into place via
 * {@code Files.move(REPLACE_EXISTING, ATOMIC_MOVE)}.
 */
package me.neveux.ccx.service;
