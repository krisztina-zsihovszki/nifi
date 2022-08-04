package org.apache.nifi.dropbox.credentials.service;

import com.dropbox.core.oauth.DbxCredential;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.processor.exception.ProcessException;

//TODO: Replace link
/**
 * DropboxCredentialService interface to support getting Dropbox
 * DbxCredential used for instantiating Dropbox client.
 *
 *
 * @see <a href="http://google.github.io/google-auth-library-java/releases/0.5.0/apidocs/com/google/auth/oauth2/GoogleCredentials.html">GoogleCredentials</a>
 */
@Tags({"dropbox", "credentials", "auth", "session"})
@CapabilityDescription("Provides DbxCredential.")
public interface DropboxCredentialService extends ControllerService {
    /**
     * Get Dropbox Credential
     * @return Valid Dropbox Credential suitable for authorizing requests on the platform.
     * @throws ProcessException process exception in case there is problem in getting credentials
     */
    DbxCredential getDropboxCredential() throws ProcessException;
}
