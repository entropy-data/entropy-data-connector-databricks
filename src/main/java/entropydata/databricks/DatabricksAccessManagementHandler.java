package entropydata.databricks;

import com.databricks.sdk.AccountClient;
import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.core.error.platform.NotFound;
import com.databricks.sdk.service.catalog.PermissionsChange;
import com.databricks.sdk.service.catalog.Privilege;
import com.databricks.sdk.service.catalog.SchemaInfo;
import com.databricks.sdk.service.catalog.SecurableType;
import com.databricks.sdk.service.catalog.UpdatePermissions;
import com.databricks.sdk.service.catalog.UpdatePermissionsResponse;
import com.databricks.sdk.service.iam.ComplexValue;
import com.databricks.sdk.service.iam.Group;
import com.databricks.sdk.service.iam.ListAccountGroupsRequest;
import com.databricks.sdk.service.iam.ListAccountServicePrincipalsRequest;
import com.databricks.sdk.service.iam.ServicePrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.EntropyDataEventHandler;
import entropydata.sdk.client.ApiException;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessDeactivatedEvent;
import entropydata.sdk.client.model.DataProduct;
import entropydata.sdk.client.model.Team;
import entropydata.sdk.client.model.TeamMembersInner;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabricksAccessManagementHandler implements EntropyDataEventHandler {

  private static final Logger log = LoggerFactory.getLogger(DatabricksAccessManagementHandler.class);

  private final EntropyDataClient client;
  private final WorkspaceClient workspaceClient;
  private final AccountClient accountClient;
  private final ObjectMapper objectMapper;

  public DatabricksAccessManagementHandler(
      EntropyDataClient client,
      WorkspaceClient workspaceClient,
      AccountClient accountClient) {
    this.client = client;
    this.workspaceClient = workspaceClient;
    this.accountClient = accountClient;
    this.objectMapper = client.getApiClient().getObjectMapper();
  }

  @Override
  public void onAccessActivatedEvent(AccessActivatedEvent event) {
    log.info("Processing AccessActivatedEvent {}", event.getId());
    var access = getAccess(event.getId());
    if (access == null) {
      log.info("Access {} not found, skip granting permissions", event.getId());
      return;
    }
    var server = resolveProviderServer(access);
    if (server == null) {
      log.info("Access {} is not applicable for Databricks access management", access.getId());
      return;
    }
    if (!isActive(access)) {
      log.info("Access {} is not active, skip granting permissions", access.getId());
      return;
    }
    grantPermissions(access, server);
  }

  @Override
  public void onAccessDeactivatedEvent(AccessDeactivatedEvent event) {
    log.info("Processing AccessDeactivatedEvent {}", event.getId());
    var access = getAccess(event.getId());
    if (access == null) {
      log.info("Access {} not found, skip revoking permissions", event.getId());
      return;
    }
    if (resolveProviderServer(access) == null) {
      log.info("Access {} is not applicable for Databricks access management", access.getId());
      return;
    }
    revokePermissions(access);
  }

  private boolean isActive(Access access) {
    return Objects.equals(access.getInfo().getActive(), Boolean.TRUE);
  }

  /**
   * Resolves the Databricks server (catalog/schema/host) for the provider output port of this access.
   * The server details live in the linked data contract, where {@code catalog} is a first-class
   * Databricks server field, with the output port's own server config as fallback. Returns {@code null}
   * when the access is not applicable for this connector, i.e. the output port is not a Databricks port,
   * no server with catalog and schema could be resolved, or the server host does not match this
   * connector's workspace.
   */
  private Map<String, String> resolveProviderServer(Access access) {
    var provider = access.getProvider();
    if (provider == null) {
      log.debug("Abort, as no provider is available");
      return null;
    }
    var dataProductId = provider.getDataProductId();
    var outputPortId = provider.getOutputPortId();

    var dataProductMap = objectMapper.convertValue(client.getDataProductsApi().getDataProduct(dataProductId), Map.class);
    @SuppressWarnings("unchecked")
    var outputPort = findOutputPort((Map<String, Object>) dataProductMap, outputPortId);
    if (outputPort == null) {
      log.info("No output port found for dataProductId {}, outputPortId {}", dataProductId, outputPortId);
      return null;
    }

    var type = (String) outputPort.get("type");
    if (type == null || !type.equalsIgnoreCase("databricks")) {
      log.info("Output port type is not databricks for dataProductId {}, outputPortId {}", dataProductId, outputPortId);
      return null;
    }

    // Resolve server config: first try data contract, then fall back to direct server field
    var server = resolveServerFromContract(outputPort);
    if (server == null) {
      server = resolveServerFromOutputPort(outputPort);
    }
    if (server == null) {
      log.info("No server could be resolved for dataProductId {}, outputPortId {}", dataProductId, outputPortId);
      return null;
    }

    var serverHost = server.get("host");
    if (!hostnamesMatch(serverHost)) {
      log.info("Hostnames do not match: entropydata.client.databricks.workspace.host={} and server.host={}",
          workspaceClient.config().getHost(), serverHost);
      return null;
    }

    if (server.get("catalog") == null || server.get("schema") == null) {
      log.info("Server for dataProductId {}, outputPortId {} has no catalog or schema", dataProductId, outputPortId);
      return null;
    }

    return server;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> findOutputPort(Map<String, Object> dataProductMap, String outputPortId) {
    var outputPorts = (List<Map<String, Object>>) dataProductMap.get("outputPorts");
    if (outputPorts == null) {
      return null;
    }
    for (var port : outputPorts) {
      var portId = (String) port.get("id");
      var portName = (String) port.get("name");
      if (outputPortId.equals(portId) || outputPortId.equals(portName)) {
        return port;
      }
    }
    return null;
  }

  /**
   * Resolves the server from the ODCS data contract linked by the output port: servers is a list carrying
   * the name in the {@code server} field, and a Databricks server carries {@code host}, {@code catalog}
   * and {@code schema}. The contract is fetched untyped to stay independent of the bundled SDK model
   * version.
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> resolveServerFromContract(Map<String, Object> outputPort) {
    // Get the data contract ID - DPS uses "dataContractId", ODPS uses "contractId"
    var dataContractId = (String) outputPort.get("dataContractId");
    if (dataContractId == null) {
      dataContractId = (String) outputPort.get("contractId");
    }
    if (dataContractId == null) {
      // ODPS may store contractId in customProperties
      dataContractId = getCustomPropertyValue(outputPort, "contractId");
    }
    if (dataContractId == null) {
      return null;
    }

    var contractServerName = getOutputPortCustomField(outputPort, "contractServer");

    Map<String, Object> dataContract;
    try {
      dataContract = fetchDataContractAsMap(dataContractId);
    } catch (Exception e) {
      log.warn("Failed to fetch data contract {}: {}", dataContractId, e.getMessage());
      return null;
    }

    var servers = dataContract.get("servers");

    if (servers instanceof Map) {
      log.warn("Data contract {} uses the deprecated DCS format, which is not supported; migrate it to ODCS", dataContractId);
      return null;
    }

    // Open Data Contract Standard (ODCS): servers is a List with "server" field as the name
    if (servers instanceof List) {
      var serversList = (List<Map<String, Object>>) servers;
      Map<String, Object> server;
      if (contractServerName != null) {
        server = serversList.stream()
            .filter(s -> contractServerName.equals(s.get("server")))
            .findFirst().orElse(serversList.isEmpty() ? null : serversList.get(0));
      } else {
        server = serversList.isEmpty() ? null : serversList.get(0);
      }
      if (server != null) {
        return toStringMap(server);
      }
    }

    return null;
  }

  private Map<String, Object> fetchDataContractAsMap(String dataContractId) throws ApiException {
    var apiClient = client.getApiClient();
    var path = "/api/datacontracts/" + apiClient.escapeString(dataContractId);
    return apiClient.invokeAPI(
        path,
        "GET",
        new ArrayList<>(),
        new ArrayList<>(),
        "",
        null,
        new HashMap<>(),
        new HashMap<>(),
        new HashMap<>(),
        "application/json",
        null,
        new String[] {"ApiKeyAuth", "BearerAuth"},
        new TypeReference<Map<String, Object>>() {});
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> resolveServerFromOutputPort(Map<String, Object> outputPort) {
    // DPS format: direct "server" field
    if (outputPort.containsKey("server") && outputPort.get("server") instanceof Map) {
      return toStringMap((Map<String, Object>) outputPort.get("server"));
    }
    // ODPS format: server in customProperties
    if (outputPort.containsKey("customProperties") && outputPort.get("customProperties") instanceof List) {
      for (var prop : (List<Map<String, Object>>) outputPort.get("customProperties")) {
        if ("server".equals(prop.get("property")) && prop.get("value") instanceof Map) {
          return toStringMap((Map<String, Object>) prop.get("value"));
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private String getOutputPortCustomField(Map<String, Object> outputPort, String fieldName) {
    if (outputPort.get("custom") instanceof Map) {
      var custom = (Map<String, Object>) outputPort.get("custom");
      var value = custom.get(fieldName);
      if (value != null) {
        return value.toString();
      }
    }
    return getCustomPropertyValue(outputPort, fieldName);
  }

  @SuppressWarnings("unchecked")
  private String getCustomPropertyValue(Map<String, Object> map, String propertyName) {
    if (map.get("customProperties") instanceof List) {
      for (var prop : (List<Map<String, Object>>) map.get("customProperties")) {
        if (propertyName.equals(prop.get("property"))) {
          var value = prop.get("value");
          return value != null ? value.toString() : null;
        }
      }
    }
    return null;
  }

  private static Map<String, String> toStringMap(Map<String, Object> map) {
    var result = new HashMap<String, String>();
    for (var entry : map.entrySet()) {
      if (entry.getValue() != null) {
        result.put(entry.getKey(), entry.getValue().toString());
      }
    }
    return result;
  }

  private boolean hostnamesMatch(String serverHost) {
    String configHost = normalizeHost(this.workspaceClient.config().getHost());
    String normalizedServerHost = normalizeHost(serverHost);
    return normalizedServerHost != null && Objects.equals(configHost, normalizedServerHost);
  }

  private static String normalizeHost(String url) {
    if (url == null) {
      return null;
    }
    // ODCS Databricks servers may store the host without a scheme (e.g. dbc-xxx.cloud.databricks.com),
    // for which URI.getHost() returns null; assume https so the host component parses either way.
    String withScheme = url.contains("://") ? url : "https://" + url;
    try {
      String host = URI.create(withScheme).getHost();
      return host == null ? null : host.toLowerCase();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  void grantPermissions(Access access, Map<String, String> server) {
    var schemaFullName = server.get("catalog") + "." + server.get("schema");
    var accessGroupName = "access-" + access.getId();

    var accessGroup = createDatabricksGroup(accessGroupName);

    switch (consumerType(access)) {
      case DATA_PRODUCT -> {
        // create a service principal for the consumer data product
        log.info("Creating service principal for consumer data product {}", access.getConsumer().getDataProductId());
        var consumerDataProductServicePrincipalId = createDatabricksServiceProvider(access.getConsumer().getDataProductId());
        addMemberToGroup(accessGroup, consumerDataProductServicePrincipalId);

        // also add the consumer team to the access group
        log.info("Adding consumer team to access group {}", accessGroupName);
        var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
        var consumerTeamGroupName = "team-" + consumerTeam.getId();
        var teamGroup = createDatabricksGroup(consumerTeamGroupName);
        addMembersToGroup(teamGroup, getMemberEmailAddresses(consumerTeam));
        addMemberToGroup(accessGroup, teamGroup.getId());
      }
      case TEAM -> {
        var consumerTeam = getConsumerTeam(access.getConsumer().getTeamId());
        var consumerTeamGroupId = "team-" + consumerTeam.getId();
        var teamGroup = createDatabricksGroup(consumerTeamGroupId);
        addMembersToGroup(teamGroup, getMemberEmailAddresses(consumerTeam));
        addMemberToGroup(accessGroup, consumerTeamGroupId);
      }
      case USER -> {
        var userId = access.getConsumer().getUserId();
        addMemberToGroup(accessGroup, userId);
      }
    }

    grantSchemaPermissions(schemaFullName, accessGroup.getDisplayName());

    // TODO: update access resource in Entropy Data with logs
  }

  /**
   * Revoking permissions means simply deleting the Databricks group for this Access resource.
   * Databricks will take a few seconds until the permissions are also removed in UI from the secured object (i.e. schema).
   */
  private void revokePermissions(Access access) {
    String accessGroupName = "access-" + access.getId();
    Optional<Group> accessGroupOptional = getGroupByName(accessGroupName);
    if (accessGroupOptional.isEmpty()) {
      log.info("Group {} does not exist or was already deleted", accessGroupName);
      return;
    }
    log.info("Deleting access group {} for access {}", accessGroupName, access.getId());
    accountClient.groups().delete(accessGroupOptional.get().getId());
    log.info("Access group {} deleted", accessGroupName);
  }

  /**
   * Create an account group if it does not exist.
   * Workspace groups are legacy and cannot be used for unity catalog access control.
   */
  private Group createDatabricksGroup(String groupName) {
    var group = getGroupByName(groupName);
    if (group.isPresent()) {
      log.info("Group {} already exists", groupName);
      return group.get();
    }
    log.info("Creating group {}", groupName);
    var newGroup = new Group()
        .setDisplayName(groupName);
    Group createdGroup = accountClient.groups().create(newGroup);
    log.info("Created group ID={}, Name={}", createdGroup.getId(), createdGroup.getDisplayName());
    return createdGroup;
  }

  private Optional<Group> getGroupByName(String groupName) {
    Iterable<Group> groups = accountClient.groups()
        .list(new ListAccountGroupsRequest().setFilter("displayName eq \"" + groupName + "\""));
    return groups.iterator().hasNext() ? Optional.of(groups.iterator().next()) : Optional.empty();
  }

  private Optional<Group> getGroupById(String groupId) {
    try {
      return Optional.of(accountClient.groups().get(groupId));
    } catch (NotFound e) {
      return Optional.empty();
    }
  }

  private void addMemberToGroup(Group group, String principalId) {
    addMembersToGroup(group, List.of(principalId));
  }

  private void addMembersToGroup(Group group, List<String> principalIds) {
    var group1 = getGroupById(group.getId()).orElseThrow(() -> {
      log.error("Group {} does not exist", group.getId());
      return new IllegalStateException("Group " + group.getId() + " does not exist");
    });
    var changed = false;
    for (String principalId : principalIds) {
      if (group1.getMembers() != null && group1.getMembers().stream().noneMatch(m -> m.getValue().equals(principalId))) {
        log.info("Adding member {} to group {}", principalId, group.getId());
        group1.getMembers().add(new ComplexValue().setValue(principalId));
        changed = true;
      } else {
        log.info("Member {} already in group {}", principalId, group.getId());
      }
    }
    if (changed) {
      log.info("Updating group {}", group.getId());
      accountClient.groups().update(group1);
    }
  }

  private Team getConsumerTeam(String teamId) {
    return client.getTeamsApi().getTeam(teamId);
  }

  private static List<String> getMemberEmailAddresses(Team consumerTeam) {
    if (consumerTeam.getMembers() == null) {
      return Collections.emptyList();
    }
    return consumerTeam.getMembers().stream().map(TeamMembersInner::getEmailAddress).toList();
  }


  private ConsumerType consumerType(Access access) {
    //noinspection ConstantValue
    if (access.getConsumer().getDataProductId() != null) {
      return ConsumerType.DATA_PRODUCT;
    } else if (access.getConsumer().getTeamId() != null) {
      return ConsumerType.TEAM;
    } else if (access.getConsumer().getUserId() != null) {
      return ConsumerType.USER;
    }
    throw new IllegalArgumentException("Unknown consumer type");
  }

  enum ConsumerType {
    DATA_PRODUCT,
    TEAM,
    USER
  }

  /**
   * Resolves the account service principal representing a consumer data product, creating it if absent.
   * It must be an <em>account</em> principal (not a workspace-local one) so it can be a member of the
   * account group that carries the Unity Catalog grant. Returns the account SCIM id used as the group
   * member value. Looks up by display name — the SCIM {@code get} endpoint only accepts the internal id.
   */
  private String createDatabricksServiceProvider(String dataProductId) {
    DataProduct dataProduct = getDataProduct(dataProductId);
    String servicePrincipalName = getServicePrincipalName(dataProduct);

    var existing = getServicePrincipalByName(servicePrincipalName);
    if (existing.isPresent()) {
      log.info("Service principal {} already exists", servicePrincipalName);
      return existing.get().getId();
    }

    log.info("Creating service principal {} for data product {}", servicePrincipalName, dataProductId);
    ServicePrincipal created = accountClient.servicePrincipals().create(
        new ServicePrincipal()
            .setDisplayName(servicePrincipalName)
            .setExternalId(dataProductId)
            .setActive(true));
    log.info("Created service principal ID={}, Name={}", created.getId(), created.getDisplayName());
    return created.getId();
  }

  private Optional<ServicePrincipal> getServicePrincipalByName(String displayName) {
    Iterable<ServicePrincipal> servicePrincipals = accountClient.servicePrincipals()
        .list(new ListAccountServicePrincipalsRequest().setFilter("displayName eq \"" + displayName + "\""));
    var iterator = servicePrincipals.iterator();
    return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
  }

  /**
   * The service principal name for a consumer data product: a {@code databricksServicePrincipal} custom
   * property on the data product wins (to reuse the data product's real principal), otherwise a derived
   * {@code dataproduct-<id>} name. The lookup and create must use the same name so an existing principal
   * is reused instead of duplicated.
   */
  private static String getServicePrincipalName(DataProduct dataProduct) {
    if (dataProduct.getCustom() != null && dataProduct.getCustom().containsKey("databricksServicePrincipal")) {
      return dataProduct.getCustom().get("databricksServicePrincipal");
    }
    return "dataproduct-" + dataProduct.getId();
  }

  public void grantSchemaPermissions(String schemaFullName, String principal) {

    // verify that the schema exists in databricks
    SchemaInfo schemaInfo = workspaceClient.schemas().get(schemaFullName);
    if (schemaInfo == null) {
      log.error("Schema {} not found in Databricks", schemaFullName);
      return;
    }

    log.info("Granting SELECT permission to principal {} on schema {}", principal, schemaFullName);
    UpdatePermissionsResponse grantedPermissions = workspaceClient.grants().update(
        new UpdatePermissions()
        .setSecurableType(SecurableType.SCHEMA.name())
        .setFullName(schemaFullName)
        .setChanges(Collections.singleton(
            new PermissionsChange()
                .setPrincipal(principal)
                .setAdd(Collections.singleton(Privilege.SELECT))
        )));
    log.info("Granted permissions: {}", grantedPermissions);

    // TODO return log information
  }

  private Access getAccess(String accessId) {
    try {
      return client.getAccessApi().getAccess(accessId);
    } catch (ApiException e) {
      if(e.getCode() == 404) {
        log.info("Access {} not found", accessId);
        return null;
      } else {
        log.error("Error getting access", e);
        throw e;
      }
    }
  }

  private DataProduct getDataProduct(String dataProductId) {
    try {
      return objectMapper.convertValue(client.getDataProductsApi().getDataProduct(dataProductId), DataProduct.class);
    } catch (ApiException e) {
      log.error("Error getting data product", e);
      throw new RuntimeException(e);
    }
  }

}
