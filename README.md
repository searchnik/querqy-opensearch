[![Test and Build OpenSearch Querqy Plugin](https://github.com/querqy/querqy-opensearch/actions/workflows/opensearch-querqy-test-and-build-workflow.yml/badge.svg)](https://github.com/querqy/querqy-opensearch/actions/workflows/opensearch-querqy-test-and-build-workflow.yml)


# OpenSearch Querqy

Querqy is a query rewriting plugin that helps to solve relevance issues making search engines more precise regarding 
matching and scoring. Furthermore, it facilitates implementing business rules into a search by simple configurations.
Many rewriters are provided out-of-the-box, such as the CommonRulesRewriter, the WordBreakRewriter, the 
ReplaceRewriter or the NumberUnitRewriter. Additionally, Querqy is considered as a pluggable framework to facilitate 
integrating custom rewriting logic.

## Installation with released zip-packages
* Released zip-packages can be found here: [release tags](https://github.com/querqy/querqy-opensearch/tags) and on [Maven Central](https://repo1.maven.org/maven2/org/querqy/opensearch-querqy/). 
* Querqy can be installed like all other OpenSearch plugins, e.g. using

  `bin/opensearch-plugin install file:///path/to/file/opensearch-querqy.zip`

## Installation with local build
* First, you need to build the plugin by running `./gradlew build`.
* You find the build at `build/distributions/opensearch-querqy.zip`
* Querqy can be installed like all other OpenSearch plugins, e.g. using 

  `bin/opensearch-plugin install file:///path/to/file/opensearch-querqy.zip`
 
**Installation note**: In the above command, please make sure to use the `file://` protocol as the path prefix.

## Additional resources
A comprehensive documentation can be found here: [Querqy documentation](https://docs.querqy.org/querqy/index.html)

Querqy for OpenSearch can be used in the same way as Querqy for Elasticsearch, with the following exceptions:
* The installation is done calling a script named differently (see above). 
* For the definition of rewriters, paths to classes are slightly different (replacing `elasticsearch` by 
  `opensearch`). For instance, the CommonRulesRewriter could be defined as follows:

  `PUT /_plugins/_querqy/rewriter/common_rules` 
  ```json
  {
    "class": "querqy.opensearch.rewriter.SimpleCommonRulesRewriterFactory",
    "config": {
        "rules" : "notebook =>\nSYNONYM: laptop"
    }
  }
  ```
  
* In addition to Info Logging to log4j, Querqy for OpenSearch can return the applied rewrite chain in the search
  response, similar to the Querqy debug output in Solr (see below).

* Developer documentation can be found [here](docs/opensearch_dev_documentation.md)
* Developer channel: Join #querqy on the [Search Relevancy Slack space](https://opensourceconnections.com/slack)

## Returning the rewrite chain in the search response

The `querqy_debug` search response processor adds the rewriters that changed the query, and what they did, to the
`ext.querqy` section of the search response. Create a search pipeline with the processor:

`PUT /_search/pipeline/querqy-debug`
```json
{
  "response_processors": [ { "querqy_debug": {} } ]
}
```

and search with it, e.g. `POST /myindex/_search?search_pipeline=querqy-debug`:

```json
"ext": {
  "querqy": {
    "queries": [
      {
        "query": "notebook",
        "rewriters": ["common_rules"],
        "rewrite_chain": [
          {
            "rewriter_id": "common_rules",
            "actions": [
              {
                "message": "notebook#0",
                "match": { "term": "notebook", "type": "exact" },
                "instructions": [ { "type": "synonym", "value": "laptop" } ]
              }
            ]
          }
        ]
      }
    ]
  }
}
```

There is one entry for each `querqy` query in the request, including queries nested in compound queries. Rewriters
that did not change the query are not listed in `rewrite_chain`. The message is the rule's `@_log` property, or the
rule id if the rule has no `@_log` property. The details do not depend on the `info_logging` settings of the rewriters.

Things to know:
* The rewriting happens on the shards, which cannot return it with the response. The processor therefore runs the
  rewrite chain again on the coordinating node. For this, it loads and caches the rewriters on the coordinating node.
* The WordBreakCompoundRewriter needs the index of a shard and cannot run on the coordinating node. It is listed under
  `skipped_rewriters`. Rewriters after it in the chain see the query without the word break rewriting.
* The output reveals the matching rules to everyone who can search with the pipeline. Search pipelines can also be
  defined in the search request itself, so do not rely on the pipeline name to keep the rules private.
