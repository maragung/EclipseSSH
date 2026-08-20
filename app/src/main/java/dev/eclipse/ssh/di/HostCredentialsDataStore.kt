package dev.eclipse.ssh.di

import javax.inject.Qualifier

/**
 * Marks the [androidx.datastore.core.DataStore] backing
 * [dev.eclipse.ssh.data.credentials.HostCredentialStore].
 *
 * Needed because `DataStore<Preferences>` is a type the app will bind more than once — every
 * preference file has that same type — and an unqualified binding would be a landmine: the second one
 * added would either fail the build or, worse, hand the credential store somebody else's file.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HostCredentialsDataStore
