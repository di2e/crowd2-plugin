/*
 * @(#)CrowdConfigurationService.java
 * 
 * The MIT License
 * 
 * Copyright (C)2011 Thorsten Heit.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.theit.jenkins.crowd;

import static de.theit.jenkins.crowd.ErrorMessages.applicationPermission;
import static de.theit.jenkins.crowd.ErrorMessages.groupNotFound;
import static de.theit.jenkins.crowd.ErrorMessages.invalidAuthentication;
import static de.theit.jenkins.crowd.ErrorMessages.operationFailed;
import static de.theit.jenkins.crowd.ErrorMessages.userNotFound;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.atlassian.crowd.model.user.User;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.GrantedAuthorityImpl;

import com.atlassian.crowd.exception.ApplicationPermissionException;
import com.atlassian.crowd.exception.GroupNotFoundException;
import com.atlassian.crowd.exception.InvalidAuthenticationException;
import com.atlassian.crowd.exception.OperationFailedException;
import com.atlassian.crowd.exception.UserNotFoundException;
import com.atlassian.crowd.integration.http.CrowdHttpAuthenticator;
import com.atlassian.crowd.integration.http.util.CrowdHttpTokenHelper;
import com.atlassian.crowd.model.group.Group;
import com.atlassian.crowd.service.client.ClientProperties;
import com.atlassian.crowd.service.client.CrowdClient;
import org.acegisecurity.userdetails.UsernameNotFoundException;
import org.springframework.dao.DataRetrievalFailureException;

/**
 * This class contains all objects that are necessary to access the REST
 * services on the remote Crowd server. Additionally it contains some helper
 * methods to check for group membership and availability.
 * 
 * @author <a href="mailto:theit@gmx.de">Thorsten Heit (theit@gmx.de)</a>
 * @since 08.09.2011
 * @version $Id$
 */
public class CrowdConfigurationService {
	/** Used for logging purposes. */
	private static final Logger LOG = Logger.getLogger(CrowdConfigurationService.class.getName());

	/**
	 * The maximum number of groups that can be fetched from the Crowd server
	 * for a user in one request.
	 */
	private static final int MAX_GROUPS = 500;

	/** Holds the Crowd client properties. */
	ClientProperties clientProperties;

	/** The Crowd client to access the REST services on the remote Crowd server. */
	CrowdClient crowdClient;

	/** The helper class for Crowd SSO token operations. */
	CrowdHttpTokenHelper tokenHelper;

	/**
	 * The interface used to manage HTTP authentication and web/SSO
	 * authentication integration.
	 */
	CrowdHttpAuthenticator crowdHttpAuthenticator;

	/** The names of all user groups that are allowed to login. */
	ArrayList<String> allowedGroupNames;

	/** Specifies whether nested groups may be used. */
	private boolean nestedGroups;

    public boolean useSSO;

    private Cache<String, User> userCache;
	private Cache<String, Set<String>> userGroupCache;

    /**
     * Creates a new Crowd configuration object.
     *
     * @param pGroupNames
     *            The group names to use when authenticating Crowd users. May
     *            not be <code>null</code>.
     * @param pNestedGroups
     *            Specifies whether nested groups should be used when validating
     *            users against a group name.
     */
	public CrowdConfigurationService(String pGroupNames, boolean pNestedGroups) {
		if (LOG.isLoggable(Level.INFO)) {
			LOG.info("Groups given for Crowd configuration service: " + pGroupNames);
		}
		this.allowedGroupNames = new ArrayList<String>();
		for (String group : pGroupNames.split(",")) {
			if (null != group && group.trim().length() > 0) {
				if (LOG.isLoggable(Level.FINE)) {
					LOG.fine("-> adding allowed group name: " + group);
				}
				this.allowedGroupNames.add(group);
			}
		}

		this.nestedGroups = pNestedGroups;

		userCache = CacheBuilder.newBuilder().maximumSize(2500).expireAfterAccess(15, TimeUnit.MINUTES).build();
		userGroupCache = CacheBuilder.newBuilder().maximumSize(2500).expireAfterAccess(15, TimeUnit.MINUTES).build();
	}

	public User getUser(String username) {
		User user = userCache.getIfPresent(username);
		if (user == null) {
			try {
				// load the user object from the remote Crowd server
				if (LOG.isLoggable(Level.FINE)) {
					LOG.fine("Loading user object from the remote Crowd server...");
				}
				user = crowdClient.getUser(username);
			} catch (UserNotFoundException ex) {
				if (LOG.isLoggable(Level.INFO)) {
					LOG.info(userNotFound(username));
				}
				throw new UsernameNotFoundException(userNotFound(username), ex);
			} catch (ApplicationPermissionException ex) {
				LOG.warning(applicationPermission());
				throw new DataRetrievalFailureException(applicationPermission(), ex);
			} catch (InvalidAuthenticationException ex) {
				LOG.warning(invalidAuthentication());
				throw new DataRetrievalFailureException(invalidAuthentication(), ex);
			} catch (OperationFailedException ex) {
				LOG.log(Level.SEVERE, operationFailed(), ex);
				throw new DataRetrievalFailureException(operationFailed(), ex);
			}
			if (user != null) {
				userCache.put(username, user);
			}
		}

		return user;
	}

	/**
	 * Checks whether the user is a member of one of the Crowd groups whose
	 * members are allowed to login.
	 * 
	 * @param username
	 *            The name of the user to check. May not be <code>null</code> or
	 *            empty.
	 * @return <code>true</code> if and only if the group exists, is active and
	 *         the user is either a direct group member or, if nested groups may
	 *         be used, a nested group member. <code>false</code> else.
	 */
	public boolean isGroupMember(String username) {
		boolean retval = false;

		for (String group : this.allowedGroupNames) {
			retval = isGroupMember(username, group);
			if (retval) {
				break;
			}
		}

		return retval;
	}

	/**
	 * Checks whether the user is a member of the given Crowd group.
	 * 
	 * @param username
	 *            The name of the user to check. May not be <code>null</code> or
	 *            empty.
	 * @param group
	 *            The name of the group to check the user against. May not be
	 *            <code>null</code>.
	 * @return <code>true</code> if and only if the group exists, is active and
	 *         the user is either a direct group member or, if nested groups may
	 *         be used, a nested group member. <code>false</code> else.
	 * 
	 * @throws ApplicationPermissionException
	 *             If the application is not permitted to perform the requested
	 *             operation on the server.
	 * @throws InvalidAuthenticationException
	 *             If the application and password are not valid.
	 * @throws OperationFailedException
	 *             If the operation has failed for any other reason, including
	 *             invalid arguments and the operation not being supported on
	 *             the server.
	 */
	private boolean isGroupMember(String username, String group) {
		Set<String> groupNames = getGroupNamesForUser(username);
		return groupNames.contains(group);
	}

	/**
	 * Checks if the specified group name exists on the remote Crowd server and
	 * is active.
	 * 
	 * @param groupName
	 *            The name of the group to check. May not be <code>null</code>
	 *            or empty.
	 * @return <code>true</code> if and only if the group name is not empty,
	 *         does exist on the remote Crowd server and is active.
	 *         <code>false</code> else.
	 * @throws InvalidAuthenticationException
	 *             If the application and password are not valid.
	 * @throws ApplicationPermissionException
	 *             If the application is not permitted to perform the requested
	 *             operation on the server
	 * @throws OperationFailedException
	 *             If the operation has failed for any other reason, including
	 *             invalid arguments and the operation not being supported on
	 *             the server.
	 */
	public boolean isGroupActive(String groupName)
			throws InvalidAuthenticationException,
			ApplicationPermissionException, OperationFailedException {
		boolean retval = false;

		try {
			if (LOG.isLoggable(Level.FINE)) {
				LOG.fine("Checking whether group is active: " + groupName);
			}
			Group group = this.crowdClient.getGroup(groupName);
			if (null != group) {
				retval = group.isActive();
			}
		} catch (GroupNotFoundException ex) {
			if (LOG.isLoggable(Level.FINE)) {
				LOG.fine(groupNotFound(groupName));
			}
		}

		return retval;
	}

	/**
	 * Retrieves the list of all (nested) groups from the Crowd server that the
	 * user is a member of.
	 * 
	 * @param username
	 *            The name of the user. May not be <code>null</code>.
	 * @return The list of all groups that the user is a member of. Always
	 *         non-null.
	 */
	public Collection<GrantedAuthority> getAuthoritiesForUser(String username) {
		Collection<GrantedAuthority> authorities = new TreeSet<GrantedAuthority>(
				new Comparator<GrantedAuthority>() {
					@Override
					public int compare(GrantedAuthority ga1,
							GrantedAuthority ga2) {
						return ga1.getAuthority().compareTo(ga2.getAuthority());
					}
				});

		Set<String> groupNames = getGroupNamesForUser(username);

		// now create the list of authorities
		for (String str : groupNames) {
			authorities.add(new GrantedAuthorityImpl(str));
		}

		return authorities;
	}

	private Set<String> getGroupNamesForUser(String username) {
		Set<String> groupNames = userGroupCache.getIfPresent(username);

		if (groupNames == null) {
			groupNames = new HashSet<>();
			// retrieve the names of all groups the user is a direct member of
			try {
				int index = 0;
				if (LOG.isLoggable(Level.FINE)) {
					LOG.fine("Retrieve list of groups with direct membership for user '"
							+ username + "'...");
				}
				while (true) {
					if (LOG.isLoggable(Level.FINEST)) {
						LOG.finest("Fetching groups [" + index + "..."
								+ (index + MAX_GROUPS - 1) + "]...");
					}
					List<Group> groups = this.crowdClient.getGroupsForUser(
							username, index, MAX_GROUPS);
					if (null == groups || groups.isEmpty()) {
						break;
					}
					for (Group group : groups) {
						if (group.isActive()) {
							groupNames.add(group.getName());
						}
					}
					index += MAX_GROUPS;
				}
			} catch (UserNotFoundException ex) {
				if (LOG.isLoggable(Level.INFO)) {
					LOG.info(userNotFound(username));
				}
			} catch (InvalidAuthenticationException ex) {
				LOG.warning(invalidAuthentication());
			} catch (ApplicationPermissionException ex) {
				LOG.warning(applicationPermission());
			} catch (OperationFailedException ex) {
				LOG.log(Level.SEVERE, operationFailed(), ex);
			}

			// now the same but for nested group membership if this configuration
			// setting is active/enabled
			if (this.nestedGroups) {
				try {
					int index = 0;
					if (LOG.isLoggable(Level.FINE)) {
						LOG.fine("Retrieve list of groups with direct membership for user '"
								+ username + "'...");
					}
					while (true) {
						if (LOG.isLoggable(Level.FINEST)) {
							LOG.finest("Fetching groups [" + index + "..."
									+ (index + MAX_GROUPS - 1) + "]...");
						}
						List<Group> groups = this.crowdClient
								.getGroupsForNestedUser(username, index, MAX_GROUPS);
						if (null == groups || groups.isEmpty()) {
							break;
						}
						for (Group group : groups) {
							if (group.isActive()) {
								groupNames.add(group.getName());
							}
						}
						index += MAX_GROUPS;
					}
				} catch (UserNotFoundException ex) {
					if (LOG.isLoggable(Level.INFO)) {
						LOG.info(userNotFound(username));
					}
				} catch (InvalidAuthenticationException ex) {
					LOG.warning(invalidAuthentication());
				} catch (ApplicationPermissionException ex) {
					LOG.warning(applicationPermission());
				} catch (OperationFailedException ex) {
					LOG.log(Level.SEVERE, operationFailed(), ex);
				}
			}
			if (!groupNames.isEmpty()) {
				userGroupCache.put(username, groupNames);
			}
		}

		return groupNames;
	}

    static public Properties getProperties(String url, String applicationName, String password,
                                           int sessionValidationInterval, boolean useSSO,
                                           String cookieDomain, String cookieTokenkey, Boolean useProxy,
                                           String httpProxyHost, String httpProxyPort, String httpProxyUsername,
                                           String httpProxyPassword, String socketTimeout,
                                           String httpTimeout, String httpMaxConnections){
        // for https://docs.atlassian.com/crowd/2.7.1/com/atlassian/crowd/service/client/ClientPropertiesImpl.html
        Properties props = new Properties();

        String crowdUrl = url;
        if (!crowdUrl.endsWith("/")) {
            crowdUrl += "/";
        }
        props.setProperty("application.name", applicationName);
        props.setProperty("application.password", password);
        props.setProperty("crowd.base.url", crowdUrl);
        props.setProperty("application.login.url", crowdUrl + "console/");
        props.setProperty("crowd.server.url", crowdUrl + "services/");
        props.setProperty("session.validationinterval",	String.valueOf(sessionValidationInterval));
        //TODO move other values to jenkins web configuration
        props.setProperty("session.isauthenticated", "session.isauthenticated");
        props.setProperty("session.tokenkey", "session.tokenkey");
        props.setProperty("session.lastvalidation","session.lastvalidation");

        if (useSSO) {
            if (cookieDomain != null && !cookieDomain.equals(""))
                props.setProperty("cookie.domain", cookieDomain);
            if (cookieTokenkey != null && !cookieTokenkey.equals(""))
                props.setProperty("cookie.tokenkey", cookieTokenkey);
        }

        if (useProxy != null && useProxy){
            if (httpProxyHost != null && !httpProxyHost.equals(""))
                props.setProperty("http.proxy.host", httpProxyHost);
            if (httpProxyPort != null && !httpProxyPort.equals(""))
                props.setProperty("http.proxy.port", httpProxyPort);
            if (httpProxyUsername != null && !httpProxyUsername.equals(""))
                props.setProperty("http.proxy.username", httpProxyUsername);
            if (httpProxyPassword != null && !httpProxyPassword.equals(""))
                props.setProperty("http.proxy.password", httpProxyPassword);
        }

        if (socketTimeout != null && !socketTimeout.equals(""))
            props.setProperty("socket.timeout", socketTimeout);
        if (httpMaxConnections != null && !httpMaxConnections.equals(""))
            props.setProperty("http.max.connections", httpMaxConnections);
        if (httpTimeout != null && !httpTimeout.equals(""))
            props.setProperty("http.timeout", httpTimeout);

        return props;
    }
}
