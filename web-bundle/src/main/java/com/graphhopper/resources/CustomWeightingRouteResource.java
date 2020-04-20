/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.MultiException;
import com.graphhopper.config.ProfileConfig;
import com.graphhopper.http.WebHelper;
import com.graphhopper.jackson.CustomRequest;
import com.graphhopper.jackson.Jackson;
import com.graphhopper.routing.util.CustomModel;
import com.graphhopper.routing.weighting.custom.CustomProfileConfig;
import com.graphhopper.util.Helper;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static com.graphhopper.util.Parameters.Routing.*;

/**
 * Resource to use GraphHopper in a remote client application like mobile or browser. Note: If type
 * is json it returns the points in GeoJson array format [longitude,latitude] unlike the format "lat,lon"
 * used for the request. See the full API response format in docs/web/api-doc.md
 *
 * @author Peter Karich
 */
@Path("custom")
public class CustomWeightingRouteResource {

    private static final Logger logger = LoggerFactory.getLogger(CustomWeightingRouteResource.class);

    private final GraphHopper graphHopper;
    private final ObjectMapper yamlOM;

    @Inject
    public CustomWeightingRouteResource(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
        this.yamlOM = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(@NotNull CustomRequest request, @Context HttpServletRequest httpReq) {
        StopWatch sw = new StopWatch().start();
        String weightingVehicleLogStr = "weighting: " + request.getHints().getString("weighting", "")
                + ", vehicle: " + request.getHints().getString("vehicle", "");
        GHResponse ghResponse = new GHResponse();
        CustomModel model = request.getModel();
        if (model == null)
            throw new IllegalArgumentException("No custom model properties found");
        if (request.getHints().has(BLOCK_AREA))
            throw new IllegalArgumentException("Instead of block_area define the geometry under 'areas' as GeoJSON and use 'area_<id>: 0' in e.g. priority");
        if (Helper.isEmpty(request.getProfile()))
            throw new IllegalArgumentException("The 'profile' parameter for CustomRequest is required");

        ProfileConfig profile = graphHopper.getProfile(request.getProfile());
        if (profile == null)
            throw new IllegalArgumentException("profile '" + request.getProfile() + "' not found");
        if (!(profile instanceof CustomProfileConfig))
            throw new IllegalArgumentException("profile '" + request.getProfile() + "' cannot be used for a custom request because it has weighting=" + profile.getWeighting());

        request.putHint(Parameters.CH.DISABLE, true);
        request.putHint(CustomModel.KEY, model);
        graphHopper.calcPaths(request, ghResponse);

        boolean instructions = request.getHints().getBool(INSTRUCTIONS, true);
        boolean enableElevation = request.getHints().getBool("elevation", false);
        boolean calcPoints = request.getHints().getBool(CALC_POINTS, true);
        boolean pointsEncoded = request.getHints().getBool("points_encoded", true);

        long took = sw.stop().getNanos() / 1_000_000;
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String queryString = httpReq.getQueryString() == null ? "" : (httpReq.getQueryString() + " ");
        String logStr = queryString + infoStr + " " + request.getPoints().size() + ", took: "
                + String.format("%.1f", (double) took) + " ms, algo: " + request.getAlgorithm() + ", profile: " + request.getProfile()
                + ", " + weightingVehicleLogStr;

        if (ghResponse.hasErrors()) {
            logger.error(logStr + ", errors:" + ghResponse.getErrors());
            throw new MultiException(ghResponse.getErrors());
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().getSize()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return Response.ok(WebHelper.jsonObject(ghResponse, instructions, calcPoints, enableElevation, pointsEncoded, took)).
                    header("X-GH-Took", "" + Math.round(took * 1000)).
                    build();
        }
    }

    @POST
    @Consumes({"text/x-yaml", "text/yaml", "application/x-yaml", "application/yaml"})
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(String yaml, @Context HttpServletRequest httpReq) {
        CustomRequest customRequest;
        try {
            customRequest = yamlOM.readValue(yaml, CustomRequest.class);
        } catch (Exception ex) {
            // TODO should we really provide this much details to API users?
            throw new IllegalArgumentException("Incorrect YAML: " + ex.getMessage(), ex);
        }
        return doPost(customRequest, httpReq);
    }
}