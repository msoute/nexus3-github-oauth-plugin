package com.larscheidschmitzhermes.nexus3.github.oauth.plugin.api;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.GithubAuthenticationException;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.GithubPrincipal;
import com.larscheidschmitzhermes.nexus3.github.oauth.plugin.configuration.GithubOauthConfiguration;

@Singleton
@Named("GithubApiClient")
public class GithubApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GithubApiClient.class);

    private HttpClient client;
    private GithubOauthConfiguration configuration;
    private ObjectMapper mapper;
    // Cache token lookups to reduce the load on Github's User API to prevent hitting the rate limit.
    private Cache<String, GithubPrincipal> tokenToPrincipalCache;

    public GithubApiClient() {
        //no args constructor is needed
    }

    public GithubApiClient(HttpClient client, GithubOauthConfiguration configuration) {
        this.client = client;
        this.configuration = configuration;
        mapper = new ObjectMapper();
        initPrincipalCache();
    }

    @PostConstruct
    public void init() {
        client = HttpClientBuilder.create().build();
        mapper = new ObjectMapper();
        initPrincipalCache();
    }

    private void initPrincipalCache() {
        tokenToPrincipalCache = CacheBuilder.newBuilder()
                .expireAfterWrite(configuration.getPrincipalCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Inject
    public GithubApiClient(GithubOauthConfiguration configuration) {
        this.configuration = configuration;
    }

    public GithubPrincipal authz(String login, char[] token) throws GithubAuthenticationException {
        // Combine the login and the token as the cache key since they are both used to generate the principal. If either changes we should obtain a new
        // principal.
        String cacheKey = login + "|" + new String(token);
        GithubPrincipal cached = tokenToPrincipalCache.getIfPresent(cacheKey);
        if (cached != null) {
            LOGGER.debug("Using cached principal for login: {}", login);
            return cached;
        } else {
            GithubPrincipal principal = doAuthz(login, token);
            tokenToPrincipalCache.put(cacheKey, principal);
            return principal;
        }
    }

    private GithubPrincipal doAuthz(String loginName, char[] token) throws GithubAuthenticationException {

        GithubUser githubUser = retrieveGithubUser(loginName, token);

        GithubPrincipal principal = new GithubPrincipal();

        principal.setUsername(githubUser.getName() != null ? githubUser.getName() : loginName);
        principal.setRoles(generateRolesFromGithubOrgMemberships(token));

        return principal;
    }

    private GithubUser retrieveGithubUser(String loginName, char[] token) throws GithubAuthenticationException {
        try {
            HttpGet userRequest = new HttpGet(configuration.getGithubUserUri());
            userRequest.addHeader(constructGithubAuthorizationHeader(token));
            HttpResponse userResponse = client.execute(userRequest);

            if (userResponse.getStatusLine().getStatusCode() != 200) {
                LOGGER.warn("Authentication failed, status code was {}",
                        userResponse.getStatusLine().getStatusCode());
                userRequest.releaseConnection();
                throw new GithubAuthenticationException("Authentication failed.");
            }

            GithubUser githubUser = mapper.readValue(new InputStreamReader(userResponse.getEntity().getContent()), GithubUser.class);

            if (!loginName.equals(githubUser.getLogin())){
                throw new GithubAuthenticationException("Given username does not match Github Username!");
            }

            if (configuration.getGithubOrg() != null) {
                checkUserInOrg(configuration.getGithubOrg(), token);
            }
            return githubUser;
        } catch (IOException e) {
            throw new GithubAuthenticationException(e);
        }
    }

    private void checkUserInOrg(String githubOrg, char[] token ) throws GithubAuthenticationException {
        HttpGet orgsRequest = new HttpGet(configuration.getGithubUserOrgsUri());
        orgsRequest.addHeader(constructGithubAuthorizationHeader(token));
        HttpResponse orgsResponse;

        Set<GithubOrg> orgs;
        try {
            orgsResponse = client.execute(orgsRequest);
            orgs = mapper.readValue(new InputStreamReader(orgsResponse.getEntity().getContent()), new TypeReference<Set<GithubOrg>>() {});
        } catch (IOException e) {
            orgsRequest.releaseConnection();
            throw new GithubAuthenticationException(e);
        }
        if (orgs.stream().noneMatch(org -> githubOrg.equals(org.getLogin()))) {
            throw new GithubAuthenticationException("Given username not in Organization!");
        }
    }

    private Set<String> generateRolesFromGithubOrgMemberships(char[] token) throws GithubAuthenticationException{
        HttpGet teamsRequest = new HttpGet(configuration.getGithubUserTeamsUri());
        teamsRequest.addHeader(constructGithubAuthorizationHeader(token));
        HttpResponse teamsResponse;

        Set<GithubTeam> teams;
        try {
            teamsResponse = client.execute(teamsRequest);
            teams = mapper.readValue(new InputStreamReader(teamsResponse.getEntity().getContent()), new TypeReference<Set<GithubTeam>>() {});
        } catch (IOException e) {
            teamsRequest.releaseConnection();
            throw new GithubAuthenticationException(e);
        }

        return teams.stream().map(this::mapGithubTeamToNexusRole).collect(Collectors.toSet());
    }

    private String mapGithubTeamToNexusRole(GithubTeam team) {
        return team.getOrganization().getLogin() + "/" + team.getName();
    }

    private BasicHeader constructGithubAuthorizationHeader(char[] token) {
        return new BasicHeader("Authorization", "token " + new String(token));
    }

}
