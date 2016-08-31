package com.dynamo.cr.client;

import com.dynamo.cr.protocol.proto.Protocol.UserInfo;
import com.sun.jersey.api.client.Client;

public class UsersClient extends BaseClient implements IUsersClient {

    public UsersClient(String baseUriString, Client client) {
        super(client.resource(String.format("%s/users", baseUriString)));
    }

    @Override
    public UserInfo getUserInfo(String userName) throws RepositoryException {
        return wrapGet(String.format("/%s", userName), UserInfo.class);
    }
}
