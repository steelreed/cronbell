# Kubernetes CronJob

The job's container wraps its command in `sh -c`: ping `/start`, run the job, send the exit code
and the end of the output. Create a check with the CronJob's schedule and time zone, and keep its
ping URL in a Secret:

```bash
kubectl create secret generic nightly-report-cronbell \
  --from-literal=ping-url=https://cron.example.com/ping/<pingKey>
```

## CronJob

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: nightly-report
spec:
  schedule: "0 2 * * *"
  timeZone: "Asia/Ho_Chi_Minh"
  concurrencyPolicy: Forbid
  startingDeadlineSeconds: 600
  jobTemplate:
    spec:
      backoffLimit: 0
      activeDeadlineSeconds: 3600
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: job
              image: curlimages/curl:8.17.0   # your image; it needs sh and curl
              env:
                - name: PING_URL
                  valueFrom:
                    secretKeyRef:
                      name: nightly-report-cronbell
                      key: ping-url
              command: ["/bin/sh", "-c"]
              args:
                - |
                  ping() { curl -fsS -m 10 --retry 3 --retry-max-time 30 -o /dev/null "$@" || true; }
                  ping "$PING_URL/start?rid=$HOSTNAME"
                  { sleep 5; date; } > /tmp/job.log 2>&1   # the job
                  code=$?
                  cat /tmp/job.log
                  tail -c 10000 /tmp/job.log | ping --data-binary @- "$PING_URL/$code?rid=$HOSTNAME"
                  exit $code
```

Replace the `{ sleep 5; date; }` line with the job, for example `/app/report > /tmp/job.log 2>&1`.
As written it runs as is, so you can try the setup first:

```bash
kubectl apply -f nightly-report.yaml
kubectl create job --from=cronjob/nightly-report nightly-report-test   # run once now
kubectl logs -f job/nightly-report-test
```

## Notes

- **The image needs curl.** Most slim images (`distroless`, `alpine` without packages) do not have
  it: install it in the image (`apk add curl`, `apt-get install -y curl`), or use BusyBox `wget`,
  which sends `GET` pings only: `wget -q -O /dev/null -T 10 "$PING_URL/$code"`.
- **`concurrencyPolicy: Forbid`** skips a run while the previous one is still going, so two runs
  never report at the same time. The skipped run sends nothing; the run still going reports when
  it ends, or the check goes DOWN when it outlives its max runtime. With `Allow`, `rid` (the pod
  name, `$HOSTNAME`) keeps overlapping runs apart.
- **`timeZone`** needs Kubernetes 1.27 or newer. Without it the schedule runs in the time zone of
  the kube-controller-manager, usually UTC. Give the check the same zone.
- **`backoffLimit: 0`**: every retry of a failed pod would ping again. With retries, Cronbell sees
  each attempt as a run, and the check returns to UP when one succeeds.
- **`activeDeadlineSeconds`** kills a pod that runs too long, before it can ping. Cronbell reports
  that run when the check's max runtime is over; set the max runtime a little below the deadline.
- **`startingDeadlineSeconds`**: a run Kubernetes could not start in time (cluster down, no
  nodes) never pings, and Cronbell reports it missed one grace period after its time.
