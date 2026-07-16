package entropydata.databricks;

import com.databricks.sdk.AccountClient;
import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.core.DatabricksConfig;
import entropydata.sdk.EntropyDataAssetsSynchronizer;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.EntropyDataEventListener;
import entropydata.sdk.EntropyDataStateRepositoryRemote;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "entropydata")
@ConfigurationPropertiesScan("entropydata")
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public WorkspaceClient workspaceClient(DatabricksProperties properties) {
    var databricksConfig = new DatabricksConfig()
        .setHost(properties.workspace().host())
        .setClientId(properties.workspace().clientId())
        .setClientSecret(properties.workspace().clientSecret());
    // TODO support GCP and Azure
    return new WorkspaceClient(databricksConfig);
  }
  @Bean
  public AccountClient accountClient(DatabricksProperties properties) {
    var databricksConfig = new DatabricksConfig()
        .setHost(Objects.requireNonNullElse(properties.account().host(), "https://accounts.cloud.databricks.com"))
        .setAccountId(Objects.requireNonNull(properties.account().accountId(), "Databricks account ID is required"))
        .setClientId(properties.account().clientId())
        .setClientSecret(properties.account().clientSecret());
    return new AccountClient(databricksConfig);
  }

  @Bean
  public EntropyDataClient entropyDataClient(
      @Value("${entropydata.client.host}") String host,
      @Value("${entropydata.client.apikey}") String apiKey) {
    return new EntropyDataClient(host, apiKey);
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "entropydata.client.databricks.accessmanagement.enabled", havingValue = "true")
  public EntropyDataEventListener entropyDataEventListener(
      EntropyDataClient client, DatabricksProperties databricksProperties,
      WorkspaceClient workspaceClient,
      AccountClient accountClient,
      TaskExecutor taskExecutor) {
    var connectorid = databricksProperties.accessmanagement().connectorid();
    var eventHandler = new DatabricksAccessManagementHandler(client, workspaceClient, accountClient);
    var stateRepository = new EntropyDataStateRepositoryRemote(connectorid, client);
    var entropyDataEventListener = new EntropyDataEventListener(connectorid, "accessmanagement", client, eventHandler, stateRepository);
    taskExecutor.execute(entropyDataEventListener::start);
    return entropyDataEventListener;
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "entropydata.client.databricks.assets.enabled", havingValue = "true")
  public EntropyDataAssetsSynchronizer entropyDataAssetsSynchronizer(
      DatabricksProperties databricksProperties,
      EntropyDataClient client,
      WorkspaceClient workspaceClient,
      TaskExecutor taskExecutor) {
    var connectorid = databricksProperties.assets().connectorid();
    var stateRepository = new EntropyDataStateRepositoryRemote(connectorid, client);
    var assetsSupplier = new DatabricksAssetsSupplier(workspaceClient, stateRepository, databricksProperties);
    var entropyDataAssetsSynchronizer = new EntropyDataAssetsSynchronizer(connectorid, client, assetsSupplier);
    if (databricksProperties.assets().pollinterval() != null) {
      entropyDataAssetsSynchronizer.setDelay(databricksProperties.assets().pollinterval());
    }

    taskExecutor.execute(entropyDataAssetsSynchronizer::start);
    return entropyDataAssetsSynchronizer;
  }

  @Bean
  public SimpleAsyncTaskExecutor taskExecutor() {
    return new SimpleAsyncTaskExecutor();
  }

}
