<#-- Export Maven's effective model; the dependency graph supplies resolved type and scope. -->
{
  "dependencies": [
<#list dependencyMap as entry>
  <#assign p = entry.getKey()>
    {
      "groupId": "${(p.groupId!"")?json_string}",
      "artifactId": "${(p.artifactId!"")?json_string}",
      "version": "${(p.version!"")?json_string}",
      "name": "${(p.name!"")?json_string}",
      "description": "${(p.description!"")?json_string}",
      "url": "${(p.url!"")?json_string}",
      "licenses": [
  <#list p.licenses as license>
        {"name": "${(license.name!"")?json_string}", "url": "${(license.url!"")?json_string}"}<#sep>,</#sep>
  </#list>
      ]
    }<#sep>,</#sep>
</#list>
  ]
}
