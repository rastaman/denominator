package denominator.clouddns;

import java.net.URI;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import denominator.Credentials;
import denominator.Credentials.ListCredentials;
import denominator.clouddns.RackspaceApis.CloudIdentity;
import denominator.clouddns.RackspaceApis.TokenIdAndPublicURL;

/**
 * gets the current endpoint and authorization token from the identity service
 * using password or api key auth.
 */
// similar to guava MemoizingSupplier
@Singleton
class InvalidatableAuthProvider implements Provider<TokenIdAndPublicURL> {
    private final denominator.Provider provider;
    private final CloudIdentity identityService;
    private final Provider<Credentials> credentials;
    transient volatile String lastKeystoneUrl;
    transient volatile int lastCredentialsHashCode;
    transient volatile boolean initialized;
    // "value" does not need to be volatile; visibility piggy-backs
    // on above
    transient TokenIdAndPublicURL value;

    @Inject
    InvalidatableAuthProvider(denominator.Provider provider, CloudIdentity identityService,
            Provider<Credentials> credentials) {
        this.provider = provider;
        this.identityService = identityService;
        this.credentials = credentials;
        // for toString
        this.lastKeystoneUrl = provider.url();
    }

    public void invalidate() {
        initialized = false;
    }

    @Override
    public TokenIdAndPublicURL get() {
        String currentUrl = provider.url();
        Credentials currentCreds = credentials.get();

        if (needsRefresh(currentUrl, currentCreds)) {
            synchronized (this) {
                if (needsRefresh(currentUrl, currentCreds)) {
                    lastCredentialsHashCode = currentCreds.hashCode();
                    lastKeystoneUrl = currentUrl;
                    TokenIdAndPublicURL t = auth(currentCreds);
                    value = t;
                    initialized = true;
                    return t;
                }
            }
        }
        return value;
    }

    private boolean needsRefresh(String currentUrl, Credentials currentCreds) {
        return !initialized || currentCreds.hashCode() != lastCredentialsHashCode
                || !currentUrl.equals(lastKeystoneUrl);
    }

    private TokenIdAndPublicURL auth(Credentials currentCreds) {
        URI url = URI.create(lastKeystoneUrl);
        if (currentCreds instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> mapCreds = Map.class.cast(currentCreds);
            String username = mapCreds.get("username");
            if (mapCreds.containsKey("apiKey")) {
                return identityService.apiKeyAuth(url, username, mapCreds.get("apiKey"));
            }
            return identityService.passwordAuth(url, username, mapCreds.get("password"));
        }
        List<Object> listCreds = ListCredentials.asList(currentCreds);
        return identityService.apiKeyAuth(url, listCreds.get(0).toString(), listCreds.get(1).toString());
    }

    @Override
    public String toString() {
        return "InvalidatableAuthSupplier(" + lastKeystoneUrl + ")";
    }
}