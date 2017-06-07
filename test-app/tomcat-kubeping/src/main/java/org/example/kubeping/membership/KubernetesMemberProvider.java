package org.example.kubeping.membership;

import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.membership.MemberImpl;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.example.kubeping.stream.StreamProvider;
import org.example.kubeping.stream.TokenStreamProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;

public class KubernetesMemberProvider implements MemberProvider {
    private static final Log log = LogFactory.getLog(DynamicMembershipService.class);

    // TODO: what about "pure" Kubernetes?
    private static final String ENV_PREFIX = "OPENSHIFT_KUBE_PING_";

    private String url;
    private StreamProvider streamProvider;
    private int connectionTimeout;
    private int readTimeout;
    private int port;

    private static String getEnv(String... keys) {
        String val = null;

        for (String key : keys) {
            val = AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getenv(key));
            if (val != null)
                break;
        }

        return val;
    }

    private static byte[] idToBytes(String id) {
        if (id == null)
            return null;

        id = id.replaceAll("-", "");
        if (id.length() < 32)
            return null;

        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(id.substring(i * 2, i * 2 + 2), 16);

        return bytes;
    }

    @Override
    public void init(Properties properties) throws IOException {
        connectionTimeout = Integer.parseInt(properties.getProperty("connectionTimeout", "1000"));
        readTimeout = Integer.parseInt(properties.getProperty("readTimeout", "1000"));
        port = Integer.parseInt(properties.getProperty("tcpListenPort"));

        String namespace = getEnv(ENV_PREFIX + "NAMESPACE");
        if (namespace == null || namespace.length() == 0)
            throw new RuntimeException("Namespace not set; clustering disabled");

        log.info(String.format("Namespace [%s] set; clustering enabled", namespace));

        String protocol = getEnv(ENV_PREFIX + "MASTER_PROTOCOL");
        String masterHost;
        String masterPort;

        String certFile = getEnv(ENV_PREFIX + "CLIENT_CERT_FILE", "KUBERNETES_CLIENT_CERTIFICATE_FILE");

        if (certFile == null) {
            if (protocol == null)
                protocol = "https";

            masterHost = getEnv(ENV_PREFIX + "MASTER_HOST", "KUBERNETES_SERVICE_HOST");
            masterPort = getEnv(ENV_PREFIX + "MASTER_PORT", "KUBERNETES_SERVICE_PORT");
            String saTokenFile = getEnv(ENV_PREFIX + "SA_TOKEN_FILE");
            if (saTokenFile == null)
                saTokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";

            byte[] bytes = Files.readAllBytes(FileSystems.getDefault().getPath(saTokenFile));
            String saToken = new String(bytes);

            String caCertFile = getEnv(ENV_PREFIX + "CA_CERT_FILE", "KUBERNETES_CA_CERTIFICATE_FILE");
            if (caCertFile == null)
                caCertFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

            streamProvider = new TokenStreamProvider(saToken, caCertFile);
        } else {
            if (protocol == null)
                protocol = "http";

            masterHost = getEnv(ENV_PREFIX + "MASTER_HOST", "KUBERNETES_RO_SERVICE_HOST");
            masterPort = getEnv(ENV_PREFIX + "MASTER_PORT", "KUBERNETES_RO_SERVICE_PORT");

            String keyFile = getEnv(ENV_PREFIX + "CLIENT_KEY_FILE", "KUBERNETES_CLIENT_KEY_FILE");
            String keyPassword = getEnv(ENV_PREFIX + "CLIENT_KEY_PASSWORD", "KUBERNETES_CLIENT_KEY_PASSWORD");

            String keyAlgo = getEnv(ENV_PREFIX + "CLIENT_KEY_ALGO", "KUBERNETES_CLIENT_KEY_ALGO");
            if (keyAlgo == null)
                keyAlgo = "RSA";

            String caCertFile = getEnv(ENV_PREFIX + "CA_CERT_FILE", "KUBERNETES_CA_CERTIFICATE_FILE");
            if (caCertFile == null)
                caCertFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";

            // TODO
            throw new NotImplementedException();
            // CertificateStreamProvider isn't implemented yet
            // streamProvider = new CertificateStreamProvider(certFile, keyFile, keyPassword, keyAlgo, caCertFile);
        }

        String ver = getEnv(ENV_PREFIX + "API_VERSION");
        if (ver == null)
            ver = "v1";

        String labels = getEnv(ENV_PREFIX + "LABELS");

        namespace = URLEncoder.encode(namespace, "UTF-8");
        labels = labels == null ? null : URLEncoder.encode(labels, "UTF-8");

        url = String.format("%s://%s:%s/api/%s/namespaces/%s/pods", protocol, masterHost, masterPort, ver, namespace);
        if (labels != null && labels.length() > 0)
            url = url + "?labelSelector=" + labels;
    }

    @Override
    public List<? extends Member> getMembers() throws Exception {
        Map<String, String> headers = new HashMap<>();
        List<MemberImpl> members = new ArrayList<>();

        InputStream stream = streamProvider.openStream(url, headers, connectionTimeout, readTimeout);
        JSONObject json = new JSONObject(new JSONTokener(stream));

        JSONArray items = json.getJSONArray("items");

        for (int i = 0; i < items.length(); i++) {
            String phase;
            String ip;
            String uid;

            try {
                JSONObject item = items.getJSONObject(i);
                JSONObject status = item.getJSONObject("status");
                JSONObject metadata = item.getJSONObject("metadata");
                phase = status.getString("phase");
                ip = status.getString("podIP");
                uid = metadata.getString("uid");
            } catch (JSONException e) {
                log.warn("JSON Exception: ", e);
                continue;
            }

            if (!phase.equals("Running"))
                continue;

            // TODO: how to handle current pod?

            byte[] id = idToBytes(uid);
            if (id == null) {
                log.info("UID " + uid + " malformed; ignoring this pod");
                continue;
            }

            MemberImpl member = null;
            try {
                member = new MemberImpl(ip, port, 0);
            } catch (IOException e) {
                // TODO
                log.warn("Exception: ", e);
                continue;
            }

            member.setUniqueId(id);
            members.add(member);
        }

        return members;
    }
}
