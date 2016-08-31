package com.dynamo.cr.client;

import java.net.URI;

import javax.ws.rs.core.UriBuilder;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com.dynamo.cr.client.filter.DefoldAuthFilter;
import com.dynamo.cr.common.providers.ProtobufProviders;
import com.dynamo.cr.protocol.proto.Protocol.ProjectInfo;
import com.dynamo.cr.protocol.proto.Protocol.UserInfo;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;

public class ClientFactory implements IClientFactory {
    private Client client;
    private String branchRoot;
    private BranchLocation branchLocation;
    private String email;
    private String authToken;
    private final String baseUriString;
    /**
     * User info of the logged in user.
     */
    private UserInfo userInfo;

    /**
     * ClientFactory constructor.
     *
     * @note branchRoot, email and password is only used for local branches
     *
     * @param branchLocation
     * @param branchRoot
     * @param email
     * @param password
     */
    public ClientFactory(BranchLocation branchLocation, String branchRoot, String email, String authToken, String baseUriString) {

        ClientConfig cc = new DefaultClientConfig();
        cc.getClasses().add(ProtobufProviders.ProtobufMessageBodyReader.class);
        cc.getClasses().add(ProtobufProviders.ProtobufMessageBodyWriter.class);
        this.client = Client.create(cc);

        DefoldAuthFilter authFilter = new DefoldAuthFilter(email, authToken);
        client.addFilter(authFilter);

        this.branchRoot = branchRoot;
        this.branchLocation = branchLocation;
        this.email = email;
        this.authToken = authToken;
        this.baseUriString = baseUriString;
    }

    private <T> T wrapGet(WebResource resource, String path, Class<T> klass) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).get(ClientResponse.class);
            if (resp.getStatus() != 200) {
                ClientUtils.throwRespositoryException(resp);
            }
            return resp.getEntity(klass);
        }
        catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
            return null; // Never reached
        }
    }

    @Override
    public IProjectClient getProjectClient(URI uri) throws RepositoryException {

        if (branchLocation == BranchLocation.REMOTE)  {
            return new ProjectClient(this, uri, client);
        }

        WebResource resource = client.resource(uri);
        ProjectInfo projectInfo = wrapGet(resource, "/project_info", ProjectInfo.class);

        return new LocalProjectClient(uri, client, getUserInfo(), projectInfo, branchRoot, email, authToken);
    }

    @Override
    public IProjectsClient getProjectsClient() {
    	String projectsUriString = String.format("%s/projects/%d", baseUriString, userInfo.getId());
        URI projectsUri = UriBuilder.fromUri(projectsUriString).build();
        return new ProjectsClient(projectsUri, client);
    }

    @Override
    public IBranchClient getBranchClient(URI uri) throws RepositoryException {

    	if (branchLocation == BranchLocation.REMOTE)  {
            return new BranchClient(this, uri, client);
        }

        IPath projectPath = new Path(uri.getPath()).removeLastSegments(2);
        URI projectURI = UriBuilder.fromUri(uri).replacePath(projectPath.toPortableString()).build();

        WebResource resource = client.resource(projectURI);
        ProjectInfo projectInfo = wrapGet(resource, "/project_info", ProjectInfo.class);

        return new LocalBranchClient(uri, getUserInfo(), projectInfo, branchRoot, email, authToken);
    }

    @Override
    public IUsersClient getUsersClient() {
        return new UsersClient(baseUriString, client);
    }

    private UserInfo getUserInfo() throws RepositoryException {
    	if (this.userInfo == null) {
    		this.userInfo = getUsersClient().getUserInfo(email);
    	}
    	return userInfo;
    }
}
