package entropydata.databricks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.databricks.sdk.AccountClient;
import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.service.catalog.UpdatePermissions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.client.ApiClient;
import entropydata.sdk.client.api.AccessApi;
import entropydata.sdk.client.api.DataProductsApi;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessProvider;
import entropydata.sdk.client.model.DataUsageAgreementConsumer;
import entropydata.sdk.client.model.DataUsageAgreementInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DatabricksAccessManagementHandlerTest {

  private EntropyDataClient client;
  private AccessApi accessApi;
  private DataProductsApi dataProductsApi;
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
    workspaceClient = mock(WorkspaceClient.class, RETURNS_DEEP_STUBS);
    accountClient = mock(AccountClient.class, RETURNS_DEEP_STUBS);

    apiClient = mock(ApiClient.class);
    objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    when(client.getApiClient()).thenReturn(apiClient);
    when(apiClient.getObjectMapper()).thenReturn(objectMapper);
    when(client.getAccessApi()).thenReturn(accessApi);
    when(client.getDataProductsApi()).thenReturn(dataProductsApi);

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
    verify(workspaceClient.grants()).update(any(UpdatePermissions.class));
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
    verify(workspaceClient.grants()).update(any(UpdatePermissions.class));
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
    verify(workspaceClient.grants()).update(any(UpdatePermissions.class));
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYaml(String name) throws IOException {
    try (InputStream is = DatabricksAccessManagementHandlerTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new Yaml().load(is);
    }
  }

}
