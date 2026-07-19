# My Number Python Worker

Local dry-run:

```bash
python worker/python/open_jpn_mynumber_worker.py --init-db --dry-run
python worker/python/open_jpn_mynumber_worker.py verifyJpki '{"person_ref":"p_demo","certificate_pem":"demo","purpose_code":"identity-proofing"}'
```

LangServer mode:

```bash
export AGENTGATEWAY_MCP_URL=zeebe-gateway:26500
python worker/python/open_jpn_mynumber_worker.py serve
```

The worker intentionally uses mock adapters unless
`OPEN_JPN_MYNUMBER_ADAPTER_MODE=real` is configured by a reviewed deployment.
