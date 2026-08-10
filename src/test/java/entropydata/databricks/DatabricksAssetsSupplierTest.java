package entropydata.databricks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.service.catalog.CatalogInfo;
import com.databricks.sdk.service.catalog.CatalogType;
import com.databricks.sdk.service.catalog.ListCatalogsRequest;
import com.databricks.sdk.service.catalog.ListSchemasRequest;
import com.databricks.sdk.service.catalog.ListTablesRequest;
import com.databricks.sdk.service.catalog.SchemaInfo;
import com.databricks.sdk.service.catalog.TableInfo;
import entropydata.databricks.DatabricksProperties.AccessmanagementProperties;
import entropydata.databricks.DatabricksProperties.AccountProperties;
import entropydata.databricks.DatabricksProperties.AssetsProperties;
import entropydata.databricks.DatabricksProperties.FilterProperties;
import entropydata.databricks.DatabricksProperties.WorkspaceProperties;
import entropydata.sdk.EntropyDataAssetsProvider.AssetCallback;
import entropydata.sdk.EntropyDataStateRepository;
import entropydata.sdk.client.model.Asset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DatabricksAssetsSupplierTest {

  private WorkspaceClient workspaceClient;
  private EntropyDataStateRepository stateRepository;

  @BeforeEach
  void setUp() {
    workspaceClient = mock(WorkspaceClient.class, RETURNS_DEEP_STUBS);
    stateRepository = mock(EntropyDataStateRepository.class);
    when(stateRepository.getState()).thenReturn(Map.of());

    when(workspaceClient.catalogs().list(any(ListCatalogsRequest.class))).thenReturn(List.of(
        new CatalogInfo().setName("prod").setFullName("prod").setCatalogType(CatalogType.MANAGED_CATALOG).setUpdatedAt(1L)));
    when(workspaceClient.schemas().list(any(ListSchemasRequest.class))).thenReturn(List.of(
        schema("sales"), schema("tmp_import")));
    when(workspaceClient.tables().list(any(ListTablesRequest.class))).thenReturn(List.of(
        table("orders"), table("orders_backup")));
  }

  @Test
  void requestsPagedResultsToBoundMemoryUsage() {
    supplier(assets(null, null, null)).fetchAssets(collectAssets(new ArrayList<>()));

    var catalogsRequest = ArgumentCaptor.forClass(ListCatalogsRequest.class);
    verify(workspaceClient.catalogs()).list(catalogsRequest.capture());
    assertThat(catalogsRequest.getValue().getMaxResults()).isZero();

    var schemasRequest = ArgumentCaptor.forClass(ListSchemasRequest.class);
    verify(workspaceClient.schemas()).list(schemasRequest.capture());
    assertThat(schemasRequest.getValue().getMaxResults()).isZero();
    assertThat(schemasRequest.getValue().getCatalogName()).isEqualTo("prod");

    var tablesRequest = ArgumentCaptor.forClass(ListTablesRequest.class);
    verify(workspaceClient.tables(), org.mockito.Mockito.atLeastOnce()).list(tablesRequest.capture());
    assertThat(tablesRequest.getValue().getMaxResults()).isZero();
    assertThat(tablesRequest.getValue().getOmitProperties()).isTrue();
    assertThat(tablesRequest.getValue().getOmitUsername()).isTrue();
  }

  @Test
  void synchronizesEverythingWhenNoFiltersAreConfigured() {
    var assets = new ArrayList<Asset>();

    supplier(assets(null, null, null)).fetchAssets(collectAssets(assets));

    assertThat(qualifiedNames(assets)).contains("prod", "prod.sales", "prod.tmp_import", "prod.sales.orders");
  }

  @Test
  void skipsExcludedSchemasAndTables() {
    var assets = new ArrayList<Asset>();
    var properties = assets(null,
        new FilterProperties(List.of(), List.of("tmp_*")),
        new FilterProperties(List.of(), List.of("*_backup")));

    supplier(properties).fetchAssets(collectAssets(assets));

    assertThat(qualifiedNames(assets)).contains("prod.sales", "prod.sales.orders");
    assertThat(qualifiedNames(assets)).doesNotContain("prod.tmp_import", "prod.sales.orders_backup");
  }

  @Test
  void takesTheWatermarkFromTheStartOfTheRunInsteadOfTheEntitiesItSaw() {
    var before = System.currentTimeMillis();

    supplier(assets(null, null, null)).fetchAssets(collectAssets(new ArrayList<>()));

    var state = ArgumentCaptor.forClass(Map.class);
    verify(stateRepository).saveState(state.capture());
    var watermark = (Long) state.getValue().get("lastUpdatedAt");

    // Anything modified while the run was in progress must be read again by the next run
    assertThat(watermark).isLessThanOrEqualTo(before);
    assertThat(watermark).isGreaterThan(before - Duration.ofHours(2).toMillis());
  }

  @Test
  void skipsCatalogsThatAreNotIncluded() {
    var assets = new ArrayList<Asset>();
    var properties = assets(new FilterProperties(List.of("analytics_*"), List.of()), null, null);

    supplier(properties).fetchAssets(collectAssets(assets));

    assertThat(assets).isEmpty();
  }

  private DatabricksAssetsSupplier supplier(AssetsProperties assetsProperties) {
    var properties = new DatabricksProperties(
        new WorkspaceProperties("https://example.cloud.databricks.com", "client-id", "client-secret"),
        new AccountProperties("https://accounts.cloud.databricks.com", "account-id", "client-id", "client-secret"),
        assetsProperties,
        new AccessmanagementProperties(false, "databricks-access-management"));
    return new DatabricksAssetsSupplier(workspaceClient, stateRepository, properties);
  }

  private static AssetsProperties assets(FilterProperties catalogs, FilterProperties schemas, FilterProperties tables) {
    return new AssetsProperties(true, "databricks-assets", Duration.parse("PT10M"), catalogs, schemas, tables);
  }

  private static SchemaInfo schema(String name) {
    return new SchemaInfo()
        .setName(name)
        .setFullName("prod." + name)
        .setCatalogName("prod")
        .setSchemaId("schema-" + name)
        .setUpdatedAt(1L);
  }

  private static TableInfo table(String name) {
    return new TableInfo()
        .setName(name)
        .setFullName("prod.sales." + name)
        .setCatalogName("prod")
        .setSchemaName("sales")
        .setTableId("table-" + name)
        .setUpdatedAt(1L);
  }

  private static AssetCallback collectAssets(List<Asset> assets) {
    return new AssetCallback() {
      @Override
      public void onAssetUpdated(Asset asset) {
        assets.add(asset);
      }

      @Override
      public void onAssetDeleted(String id) {
      }
    };
  }

  private static List<String> qualifiedNames(List<Asset> assets) {
    return assets.stream().map(asset -> asset.getInfo().getQualifiedName()).toList();
  }
}
