/*
 * Copyright 2015 UnboundID Corp.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */

package com.unboundid.scim2.server;

import com.unboundid.scim2.common.ScimResource;
import com.unboundid.scim2.common.exceptions.ResourceNotFoundException;
import com.unboundid.scim2.server.annotations.ResourceType;
import com.unboundid.scim2.common.exceptions.ScimException;
import com.unboundid.scim2.common.types.UserResource;
import com.unboundid.scim2.server.utils.ResourcePreparer;
import com.unboundid.scim2.server.utils.ResourceTypeDefinition;
import com.unboundid.scim2.server.utils.SimpleSearchResults;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import static com.unboundid.scim2.common.utils.ApiConstants.MEDIA_TYPE_SCIM;

/**
 * A per resource life cycle Resource Endpoint implementation.
 */
@ResourceType(
    description = "User Account",
    name = "User",
    schema = UserResource.class)
@Path("/Users")
public class TestResourceEndpoint
{
  private static final ResourceTypeDefinition RESOURCE_TYPE_DEFINITION =
      ResourceTypeDefinition.fromJaxRsResource(
          TestResourceEndpoint.class);

  /**
   * Test SCIM search.
   *
   * @param uriInfo The UriInfo.
   * @return The results.
   * @throws ScimException if an error occurs.
   */
  @GET
  @Produces(MEDIA_TYPE_SCIM)
  public SimpleSearchResults<UserResource> search(
      @Context final UriInfo uriInfo) throws ScimException
  {
    UserResource resource = new UserResource().setUserName("test");
    resource.setId("123");

    SimpleSearchResults<UserResource> results =
        new SimpleSearchResults<UserResource>(
            RESOURCE_TYPE_DEFINITION, uriInfo);
    results.add(resource);

    return results;
  }

  /**
   * Test SCIM retrieve by ID.
   *
   * @param id The ID of the resource to retrieve.
   * @param uriInfo The UriInfo.
   * @return The result.
   * @throws ScimException if an error occurs.
   */
  @Path("{id}")
  @GET
  @Produces(MEDIA_TYPE_SCIM)
  public ScimResource retrieve(
      @PathParam("id") final String id, @Context final UriInfo uriInfo)
      throws ScimException
  {
    if(id.equals("123"))
    {
      UserResource resource = new UserResource().setUserName("test");
      resource.setId("123");

      ResourcePreparer<UserResource> resourcePreparer =
          new ResourcePreparer<UserResource>(RESOURCE_TYPE_DEFINITION, uriInfo);
      return resourcePreparer.trimRetrievedResource(resource);
    }
    throw new ResourceNotFoundException("No resource with ID " + id);
  }
}