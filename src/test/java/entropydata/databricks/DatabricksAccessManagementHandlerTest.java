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
import entropydata.sdk.client.api.DataContractsApi;
import entropydata.sdk.client.api.DataProductsApi;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessProvider;
import entropydata.sdk.client.model.DataContract;
import entropydata.sdk.client.model.DataContractServersValue;
import entropydata.sdk.client.model.DataUsageAgreementConsumer;
import entropydata.sdk.client.model.DataUsageAgreementInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DatabricksAccessManagementHandlerTest {

  private EntropyDataClient client;
  private AccessApi accessApi;
  private DataProductsApi dataProductsApi;
  private DataContractsApi dataContractsApi;
  private ObjectMapper objectMapper;

  private WorkspaceClient workspaceClient;
  private AccountClient accountClient;

  private DatabricksAccessManagementHandler handler;

  @BeforeEach
  void setUp() {
    client = mock(EntropyDataClient.class);
    accessApi = mock(AccessApi.class);
    dataProductsApi = mock(DataProductsApi.class);
    dataContractsApi = mock(DataContractsApi.class);
    workspaceClient = mock(WorkspaceClient.class, RETURNS_DEEP_STUBS);
    accountClient = mock(AccountClient.class, RETURNS_DEEP_STUBS);

    var apiClient = mock(ApiClient.class);
    objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    when(client.getApiClient()).thenReturn(apiClient);
    when(apiClient.getObjectMapper()).thenReturn(objectMapper);
    when(client.getAccessApi()).thenReturn(accessApi);
    when(client.getDataProductsApi()).thenReturn(dataProductsApi);
    when(client.getDataContractsApi()).thenReturn(dataContractsApi);

    handler = new DatabricksAccessManagementHandler(client, workspaceClient, accountClient);
  }

  @Test
  void grantsOnCatalogAndSchemaResolvedFromOdcsContract() throws Exception {
    when(accessApi.getAccess("a-1")).thenReturn(activeUserAccess());
    when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-databricks-odps.yaml"));
    when(dataContractsApi.getDataContract("my-contract")).thenReturn(loadDataContract("datacontract-databricks.yaml"));
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
    when(dataContractsApi.getDataContract("my-contract")).thenReturn(loadDataContract("datacontract-databricks-schemeless-host.yaml"));
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
    when(dataContractsApi.getDataContract("my-contract")).thenReturn(loadDataContract("datacontract-databricks.yaml"));
    when(workspaceClient.config().getHost()).thenReturn("https://dbc-other.cloud.databricks.com");

    var event = new AccessActivatedEvent();
    event.setId("a-1");
    handler.onAccessActivatedEvent(event);

    verify(workspaceClient.schemas(), never()).get(anyString());
    verify(workspaceClient.grants(), never()).update(any(UpdatePermissions.class));
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

  /**
   * Load an ODCS data contract YAML and convert to the SDK DataContract model. ODCS uses servers as a
   * list; the SDK model uses servers as a map keyed by server name, matching what the API returns after
   * deserialization.
   */
  @SuppressWarnings("unchecked")
  private DataContract loadDataContract(String name) throws IOException {
    var yaml = loadYaml(name);
    var servers = yaml.get("servers");
    if (servers instanceof List) {
      var serversMap = new LinkedHashMap<String, DataContractServersValue>();
      for (var entry : (List<Map<String, Object>>) servers) {
        var serverName = (String) entry.get("server");
        var serverValue = new DataContractServersValue();
        entry.forEach((k, v) -> {
          if (!"server".equals(k)) {
            serverValue.put(k, v);
          }
        });
        serversMap.put(serverName, serverValue);
      }
      yaml.put("servers", serversMap);
    }
    return objectMapper.convertValue(yaml, DataContract.class);
  }

}
