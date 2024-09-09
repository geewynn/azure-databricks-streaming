export resourceGroup='azstream'
export resourceLocation='centralus'
export eventHubNamespace='azstreamns'
export databricksWorkspaceName='azstreamsdbrk'
export cosmosDatabaseAccount='azstreamscos'
export logAnalyticsWorkspaceName='azstreamsla'
export logAnalyticsWorkspaceRegion='centralus'

# Create a resource group
az group create --name $resourceGroup --location $resourceLocation

# Deploy resources
az deployment group create --resource-group $resourceGroup \
 --template-file ./deployresources.json --parameters \
 eventHubNamespace=$eventHubNamespace \
    databricksWorkspaceName=$databricksWorkspaceName \
 cosmosDatabaseAccount=$cosmosDatabaseAccount \
 logAnalyticsWorkspaceName=$logAnalyticsWorkspaceName \
 logAnalyticsWorkspaceRegion=$logAnalyticsWorkspaceRegion