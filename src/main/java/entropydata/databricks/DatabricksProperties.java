package entropydata.databricks;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entropydata.client.databricks")
public record DatabricksProperties(
    WorkspaceProperties workspace,
    AccountProperties account,
    AssetsProperties assets,
    AccessmanagementProperties accessmanagement
) {

  public record WorkspaceProperties(
      String host,
      String clientId,
      String clientSecret
  ) {

  }

  public record AccountProperties(
      String host,
      String accountId,
      String clientId,
      String clientSecret
  ) {

  }

  public record AssetsProperties(
      Boolean enabled,
      String connectorid,
      Duration pollinterval,
      FilterProperties catalogs,
      FilterProperties schemas,
      FilterProperties tables
  ) {

  }

  public record FilterProperties(
      List<String> include,
      List<String> exclude
  ) {

  }

  public record AccessmanagementProperties(
      Boolean enabled,
      String connectorid
      ) {

  }


}
