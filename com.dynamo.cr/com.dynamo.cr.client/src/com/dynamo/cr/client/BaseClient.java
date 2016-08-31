package com.dynamo.cr.client;

import java.net.URI;

import com.dynamo.cr.common.providers.ProtobufProviders;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class BaseClient implements IClient {
    protected  final WebResource resource;

    public BaseClient(WebResource resource) {
        this.resource = resource;
    }

    protected void wrapPut(String path, byte[] data) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).put(ClientResponse.class, data);

            if (resp.getStatus() != 200) {
                ClientUtils.throwRespositoryException(resp);
            }
        } catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
        }
    }

    protected <T> T wrapGet(String path, Class<T> klass) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).get(ClientResponse.class);
            if (resp.getStatus() != 200) {
                ClientUtils.throwRespositoryException(resp);
            }
            return resp.getEntity(klass);
        } catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
            return null; // Never reached
        }
    }

    protected void wrapPut(String path) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).put(ClientResponse.class);
            if (resp.getStatus() != 200 && resp.getStatus() != 204) {
                ClientUtils.throwRespositoryException(resp);
            }
        } catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
        }
    }

    protected <T> T wrapPost(String path, Class<T> klass) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).post(ClientResponse.class);
            if (resp.getStatus() != 200) {
                ClientUtils.throwRespositoryException(resp);
            }
            return resp.getEntity(klass);
        } catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
            return null; // Never reached
        }
    }

    protected void wrapPost(String path) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).post(ClientResponse.class);
            if (resp.getStatus() != 200 && resp.getStatus() != 204) {
                ClientUtils.throwRespositoryException(resp);
            }
        } catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
        }
    }

    protected void wrapDelete(String path) throws RepositoryException {
        try {
            ClientResponse resp = resource.path(path).accept(ProtobufProviders.APPLICATION_XPROTOBUF).delete(ClientResponse.class);
            if (resp.getStatus() != 200 && resp.getStatus() != 204) {
                ClientUtils.throwRespositoryException(resp);
            }
        } catch (ClientHandlerException e) {
            ClientUtils.throwRespositoryException(e);
        }
    }

    @Override
    public URI getURI() {
        return resource.getURI();
    }
}
