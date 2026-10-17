module.exports = {
  docs: [
    'index',
    {type: 'category', label: 'Get started', items: ['guides/installation', 'tutorials/first-trace', 'tutorials/read-a-trace']},
    {type: 'category', label: 'Use the tracer', items: ['guides/tracing', 'tutorials/batch-jobs', 'tutorials/java-integration', 'guides/troubleshooting']},
    {type: 'category', label: 'Reference and integration', items: ['reference/cli', 'reference/output', 'reference/batch', 'reference/java-api', 'guides/offline-javadoc', 'BOUNDED_TRACING', 'RUNNER_CONTRACT', 'guides/java-api-migration']},
    {
      type: 'category',
      label: 'Contribute',
      items: [
        'contributing/development',
        'contributing/architecture',
        'contributing/security-analysis',
        {
          type: 'category',
          label: 'Documentation',
          link: {type: 'doc', id: 'contributing/documentation'},
          items: [
            'contributing/documentation-authoring',
            'contributing/documentation-builds',
            'contributing/guide-archives',
            'contributing/javadoc-artifacts',
          ],
        },
        'contributing/releases',
      ],
    },
  ],
};
