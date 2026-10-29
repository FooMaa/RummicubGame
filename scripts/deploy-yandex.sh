#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Authentication stays in the local yc profile; this script never prints tokens.
yc_cli="${YC_CLI:-yc}"
yc_profile="${YC_PROFILE:-default}"
for required in YDB_CONNECTION_STRING YANDEX_FUNCTION_ID YANDEX_GATEWAY_ID YANDEX_RUNTIME_SA_ID YANDEX_GATEWAY_SA_ID YANDEX_PACKAGE_BUCKET; do
  if [ -z "${!required:-}" ]; then echo "Set $required" >&2; exit 2; fi
done
./gradlew :core:test :server:test :server:functionZip
"$yc_cli" storage s3 cp server/build/distributions/rummiclub-function.zip "s3://$YANDEX_PACKAGE_BUCKET/rummiclub-function.zip" --profile "$yc_profile" --only-show-errors
"$yc_cli" serverless function version create --function-id "$YANDEX_FUNCTION_ID" \
  --runtime java21 --entrypoint Handler --memory 512MB --execution-timeout 25s --concurrency 1 \
  --service-account-id "$YANDEX_RUNTIME_SA_ID" --tags game --min-log-level warn \
  --environment "YDB_CONNECTION_STRING=$YDB_CONNECTION_STRING,YDB_METADATA_CREDENTIALS=1,JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0" \
  --package-bucket-name "$YANDEX_PACKAGE_BUCKET" --package-object-name rummiclub-function.zip --profile "$yc_profile"
gateway_spec="$(mktemp)"
trap 'rm -f "$gateway_spec"' EXIT
python3 - "$gateway_spec" <<'PY'
import os,sys
from pathlib import Path
spec=Path('deploy/yandex-gateway.yaml').read_text()
spec=spec.replace('${FUNCTION_ID}',os.environ['YANDEX_FUNCTION_ID']).replace('${GATEWAY_SERVICE_ACCOUNT_ID}',os.environ['YANDEX_GATEWAY_SA_ID'])
Path(sys.argv[1]).write_text(spec)
PY
"$yc_cli" serverless api-gateway update "$YANDEX_GATEWAY_ID" --spec "$gateway_spec" --execution-timeout 30s --profile "$yc_profile"
"$yc_cli" serverless function set-scaling-policy "$YANDEX_FUNCTION_ID" --tag game \
  --zone-instances-limit 1 --zone-requests-limit 4 --provisioned-instances-count 0 --profile "$yc_profile"
gateway_url="$("$yc_cli" serverless api-gateway get "$YANDEX_GATEWAY_ID" --profile "$yc_profile" --format json | python3 -c 'import json,sys;print("https://"+json.load(sys.stdin)["domain"])')"
curl --fail --show-error --max-time 35 "$gateway_url/health"
echo
echo "Health check passed: $gateway_url"
