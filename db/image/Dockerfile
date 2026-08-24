# hr-portal-db: stock MySQL 8.0 plus the schema and demo dataset baked in
# as init scripts. The official image only runs /docker-entrypoint-
# initdb.d/*.sql when the data directory is EMPTY (first container start
# against a fresh volume) — it will not re-run against a volume that
# already has data, and it does not touch credentials at all. Those
# still come entirely from the MYSQL_ROOT_PASSWORD / MYSQL_DATABASE /
# MYSQL_USER / MYSQL_PASSWORD environment variables passed to `docker run`
# / docker-compose at container start, exactly as with the stock image —
# see infra/secrets.tf and infra/user_data.sh.tftpl for how those reach
# the deployed container from Secrets Manager. Nothing here hardcodes a
# credential.
#
# Files run in lexicographic order, so 01_schema.sql (creates the
# tables) must sort before 02_seed_demo_data.sql (deletes-then-inserts
# into them) — hence the rename on the second COPY.
FROM mysql:8.0

COPY init/01_schema.sql /docker-entrypoint-initdb.d/01_schema.sql
COPY seed/01_seed_demo_data.sql /docker-entrypoint-initdb.d/02_seed_demo_data.sql
