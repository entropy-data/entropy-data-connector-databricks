package entropydata.databricks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.databricks.sdk.AccountClient;
import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.service.catalog.Privilege;
import com.databricks.sdk.service.catalog.UpdatePermissions;
import com.databricks.sdk.service.iam.Group;
import com.databricks.sdk.service.iam.ComplexValue;
import com.databricks.sdk.service.iam.ListAccountServicePrincipalsRequest;
import com.databricks.sdk.service.iam.ListAccountUsersRequest;
import com.databricks.sdk.service.iam.PartialUpdate;
import com.databricks.sdk.service.iam.ServicePrincipal;
import com.databricks.sdk.service.iam.User;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.client.ApiClient;
import entropydata.sdk.client.api.AccessApi;
import entropydata.sdk.client.api.DataProductsApi;
import entropydata.sdk.client.api.TeamsApi;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessDeactivatedEvent;
import entropydata.sdk.client.model.AccessDeprovisioningSucceededReport;
import entropydata.sdk.client.model.AccessProvider;
import entropydata.sdk.client.model.AccessProvisioningFailedReport;
import entropydata.sdk.client.model.AccessProvisioningStartedReport;
import entropydata.sdk.client.model.AccessProvisioningSucceededReport;
import entropydata.sdk.client.model.DataUsageAgreementConsumer;
import entropydata.sdk.client.model.DataUsageAgreementInfo;
import entropydata.sdk.client.model.Team;
import entropydata.sdk.client.model.TeamMembersInner;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;

class DatabricksAccessManagementHandlerTest {

  private EntropyDataClient client;
  private AccessApi accessApi;
  private DataProductsApi dataProductsApi;
  private TeamsApi teamsApi;
  private ApiClient apiClient;
  private ObjectMapper objectMapper;

  private WorkspaceClient workspaceClient;
  private AccountClient accountClient;

  private DatabricksAccessManagementHandler handler;

  @BeforeEach
  void setUp() {
    client = mock(EntropyDataClient.class);
    accessApi = mock(AccessApi.class);
    dataProductsApi = mock(DataProductsApi.class);
    teamsApi = mock(TeamsApi.class);
    workspaceClient = mock(WorkspaceClient.class, RETURNS_DEEP_STUBS);
    accountClient = mock(AccountClient.class, RETURNS_DEEP_STUBS);

    apiClient = mock(ApiClient.class);
    objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    when(client.getApiClient()).thenReturn(apiClient);
    when(apiClient.getObjectMapper()).thenReturn(objectMapper);
    when(client.getAccessApi()).thenReturn(accessApi);
    when(client.getDataProductsApi()).thenReturn(dataProductsApi);
    when(client.getTeamsApi()).thenReturn(teamsApi);

    handler = new DatabricksAccessManagementHandler(client, workspaceClient, accountClient);
  }

  @Test
  void grantsOnCatalogAndSchemaResolvedFromOdcsContract() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    // catalog and schema come from the ODCS data contract server, not the output port
    verify(workspaceClient.schemas()).get("my_catalog.my_schema");
    // the full read chain is granted: USE CATALOG on the catalog, USE SCHEMA + SELECT on the schema
    var grants = ArgumentCaptor.forClass(UpdatePermissions.class);
    verify(workspaceClient.grants(), times(2)).update(grants.capture());
    assertThat(grants.getAllValues()).anySatisfy(g -> {
      assertThat(g.getSecurableType()).isEqualTo("CATALOG");
      assertThat(g.getFullName()).isEqualTo("my_catalog");
      assertThat(g.getChanges().iterator().next().getAdd()).containsExactly(Privilege.USE_CATALOG);
    });
    assertThat(grants.getAllValues()).anySatisfy(g -> {
      assertThat(g.getSecurableType()).isEqualTo("SCHEMA");
      assertThat(g.getFullName()).isEqualTo("my_catalog.my_schema");
      assertThat(g.getChanges().iterator().next().getAdd())
          .containsExactlyInAnyOrder(Privilege.USE_SCHEMA, Privilege.SELECT);
    });
  }

  @Test
  void matchesWhenContractServerHostHasNoScheme() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks-schemeless-host.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    // a scheme-less server host (as in the ODCS schema example) still matches the workspace host
    verify(workspaceClient.schemas()).get("my_catalog.my_schema");
    verify(workspaceClient.grants(), times(2)).update(any(UpdatePermissions.class));
  }

  @Test
  void skipsWhenServerHostDoesNotMatchWorkspace() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-other.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    verify(workspaceClient.schemas(), never()).get(anyString());
    verify(workspaceClient.grants(), never()).update(any(UpdatePermissions.class));
  }

  @Test
  void fallsBackToOutputPortServerWhenContractFetchFails() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp"))
        .thenReturn(loadYaml("provider-dp-databricks-odps-port-server.yaml"));
    when(apiClient.<Map<String, Object>>invokeAPI(anyString(), anyString(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("contract not found"));
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    verify(workspaceClient.schemas()).get("port_catalog.port_schema");
    verify(workspaceClient.grants(), times(2)).update(any(UpdatePermissions.class));
  }

  @Test
  void skipsWhenResolvedServerHasNoCatalog() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks-no-catalog.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    verify(workspaceClient.schemas(), never()).get(anyString());
    verify(workspaceClient.grants(), never()).update(any(UpdatePermissions.class));
  }

  @Test
  void createsAccountServicePrincipalForDataProductConsumer() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeDataProductAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp.yaml"));
    when(teamsApi.getTeam("t-1")).thenReturn(new Team().id("t-1"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");
    // no service principal exists yet -> the connector must create one
    when(accountClient.groups().create(any(Group.class))).thenReturn(new Group().setId("group-1"));
    when(accountClient.servicePrincipals().list(any(ListAccountServicePrincipalsRequest.class))).thenReturn(List.of());
    when(accountClient.servicePrincipals().create(any(ServicePrincipal.class)))
        .thenReturn(new ServicePrincipal().setId("sp-777").setDisplayName("dataproduct-consumer-dp"));

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    // the consumer data product is represented by an account service principal named after its id,
    // created via the account client (not looked up by name through the workspace SCIM get endpoint)
    var captor = ArgumentCaptor.forClass(ServicePrincipal.class);
    verify(accountClient.servicePrincipals()).create(captor.capture());
    assertThat(captor.getValue().getDisplayName()).isEqualTo("dataproduct-consumer-dp");
    verify(workspaceClient, never()).servicePrincipals();
    verify(workspaceClient.grants(), times(2)).update(any(UpdatePermissions.class));
  }

  @Test
  void reusesServicePrincipalFromCustomProperty() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeDataProductAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-custom-sp.yaml"));
    when(teamsApi.getTeam("t-1")).thenReturn(new Team().id("t-1"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");
    when(accountClient.groups().create(any(Group.class))).thenReturn(new Group().setId("group-1"));
    when(accountClient.servicePrincipals().list(any(ListAccountServicePrincipalsRequest.class)))
        .thenReturn(List.of(new ServicePrincipal().setId("sp-existing").setDisplayName("dp_custom_sp")));

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    // the databricksServicePrincipal custom property points at an existing principal, so none is created
    verify(accountClient.servicePrincipals(), never()).create(any(ServicePrincipal.class));
    verify(workspaceClient.grants(), times(2)).update(any(UpdatePermissions.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvesTeamMembersToAccountUserIds() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeTeamAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");
    when(accountClient.groups().create(any(Group.class))).thenReturn(new Group().setId("group-1"));
    when(teamsApi.getTeam("t-team")).thenReturn(new Team().id("t-team")
        .members(List.of(new TeamMembersInner().emailAddress("alice@example.com"))));
    when(accountClient.users().list(any(ListAccountUsersRequest.class)))
        .thenReturn(List.of(new User().setId("user-alice").setUserName("alice@example.com")));

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    // team members are added to the group by their Databricks account user id, not their email address
    var patch = ArgumentCaptor.forClass(PartialUpdate.class);
    verify(accountClient.groups(), atLeastOnce()).patch(patch.capture());
    var addedMemberValues = patch.getAllValues().stream()
        .flatMap(p -> p.getOperations().stream())
        .flatMap(op -> ((List<ComplexValue>) op.getValue()).stream())
        .map(ComplexValue::getValue)
        .toList();
    assertThat(addedMemberValues).contains("user-alice").doesNotContain("alice@example.com");
  }

  @Test
  void reportsProvisioningStartedThenSucceededOnGrant() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    // started is reported before the grant, succeeded after it, with the granted schema as reference
    var order = inOrder(accessApi);
    order.verify(accessApi).reportProvisioningStarted(eq("a-1"), any(AccessProvisioningStartedReport.class));
    var succeeded = ArgumentCaptor.forClass(AccessProvisioningSucceededReport.class);
    order.verify(accessApi).reportProvisioningSucceeded(eq("a-1"), succeeded.capture());
    assertThat(succeeded.getValue().getPlatform()).isEqualTo("databricks");
    assertThat(succeeded.getValue().getReference()).isEqualTo("my_catalog.my_schema");
    verify(accessApi, never()).reportProvisioningFailed(anyString(), any());
  }

  @Test
  void reportsProvisioningFailedWhenGrantThrows() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");
    when(workspaceClient.schemas().get(anyString())).thenThrow(new RuntimeException("schema exploded"));

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    // the grant throws, but the handler must swallow it after reporting so the event feed is not stalled
    handler.onAccessActivatedEvent(event);

    var order = inOrder(accessApi);
    order.verify(accessApi).reportProvisioningStarted(eq("a-1"), any(AccessProvisioningStartedReport.class));
    var failed = ArgumentCaptor.forClass(AccessProvisioningFailedReport.class);
    order.verify(accessApi).reportProvisioningFailed(eq("a-1"), failed.capture());
    assertThat(failed.getValue().getDiagnostics()).isEqualTo("schema exploded");
    verify(accessApi, never()).reportProvisioningSucceeded(anyString(), any());
  }

  @Test
  void reportsDeprovisioningStartedThenSucceededOnRevoke() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-abc.cloud.databricks.com");

    var event = new AccessDeactivatedEvent();
    event.setId("a-1");
    handler.onAccessDeactivatedEvent(event);

    var order = inOrder(accessApi);
    order.verify(accessApi).reportDeprovisioningStarted(eq("a-1"), any(AccessProvisioningStartedReport.class));
    var succeeded = ArgumentCaptor.forClass(AccessDeprovisioningSucceededReport.class);
    order.verify(accessApi).reportDeprovisioningSucceeded(eq("a-1"), succeeded.capture());
    assertThat(succeeded.getValue().getReference()).isEqualTo("access-a-1");
  }

  @Test
  void doesNotReportProvisioningWhenNotApplicable() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    stubDataContract("datacontract-databricks.yaml");
    // a different workspace host: another connector owns this access, so this one must stay silent
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-other.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    verify(accessApi, never()).reportProvisioningStarted(anyString(), any());
    verify(accessApi, never()).reportProvisioningSucceeded(anyString(), any());
    verify(accessApi, never()).reportProvisioningFailed(anyString(), any());
  }

  private void stubDataContract(String name) throws Exception {
    when(apiClient.<Map<String, Object>>invokeAPI(anyString(), anyString(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(loadYaml(name));
  }

  private Access activeUserAccess() {
    var access = new Access();
    access.setId("a-1");
    access.setProvider(new AccessProvider().dataProductId("provider-dp").outputPortId("op-databricks"));
    access.setConsumer(new DataUsageAgreementConsumer().userId("alice@example.com"));
    access.setInfo(objectMapper.convertValue(Map.of("purpose", "test", "active", true), DataUsageAgreementInfo.class));
    return access;
  }

  private Access activeDataProductAccess() {
    var access = new Access();
    access.setId("a-1");
    access.setProvider(new AccessProvider().dataProductId("provider-dp").outputPortId("op-databricks"));
    access.setConsumer(new DataUsageAgreementConsumer().dataProductId("consumer-dp").teamId("t-1"));
    access.setInfo(objectMapper.convertValue(Map.of("purpose", "test", "active", true), DataUsageAgreementInfo.class));
    return access;
  }

  private Access activeTeamAccess() {
    var access = new Access();
    access.setId("a-1");
    access.setProvider(new AccessProvider().dataProductId("provider-dp").outputPortId("op-databricks"));
    // the API fills the unused consumer field with the "unknown" sentinel rather than null
    access.setConsumer(new DataUsageAgreementConsumer().teamId("t-team").dataProductId("unknown"));
    access.setInfo(objectMapper.convertValue(Map.of("purpose", "test", "active", true), DataUsageAgreementInfo.class));
    return access;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYaml(String name) throws IOException {
    try (InputStream is = DatabricksAccessManagementHandlerTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new Yaml().load(is);
    }
  }

}
