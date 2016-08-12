package com.dynamo.cr.server.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

@Path("/status")
public class StatusResource {

    protected static Logger logger = LoggerFactory
            .getLogger(StatusResource.class);

    @GET
    //@Timed
    public String getServerStatus() {
        // Currently only used to check if the server is running
        // We could extend status-status later with more info
        // using protocol buffers
        return "OK";
    }
}

