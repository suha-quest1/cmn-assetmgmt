.PHONY: up down clean status logs demo demo-auto deploy bpmn build test \
        oidc-up oidc-down oidc-clean oidc-status oidc-logs

JAR := target/camunda-assetmgmt.jar

# The OIDC estate (docker-compose.camunda-oidc.yml) is a SECOND stack: Camunda
# 8.10 behind Keycloak, mirroring the shared demo estate on EC2. It is not a
# replacement for the lightweight stack above, and the two cannot run at once —
# they both want 8080, 26500 and 9600.
#
# It carries the Camunda platform ONLY — no worker, api-server, ledger or mocks;
# those are the lightweight stack's, and are not duplicated. `oidc-up` therefore
# pulls images but builds no jar, and `deploy-models` populates Operate with no
# application running.
OIDC := docker compose -f docker-compose.camunda-oidc.yml

up:            ## build & start Camunda 8.10 + all services
	docker compose up -d --build
	@echo "Waiting for Camunda to become healthy..."
	@until [ "$$(docker inspect -f '{{.State.Health.Status}}' camunda-orchestration 2>/dev/null)" = "healthy" ]; do sleep 3; done
	@echo "Ready:  Dashboard http://localhost:8090 | Operate/Tasklist http://localhost:8080 (demo/demo) | Emails http://localhost:8081"

down:          ## stop everything (keeps data)
	docker compose down

clean:         ## stop everything and wipe Camunda + Postgres data
	docker compose down -v
	rm -f reports/*.json reports/*.pdf

status:
	docker compose ps

logs:          ## follow worker + api logs
	docker compose logs -f worker api-server

oidc-up:       ## start the Keycloak/OIDC Camunda estate (mirrors EC2)
	$(OIDC) up -d
	@echo "Waiting for Camunda to become healthy (first boot pulls ~2 GB; allow several minutes)..."
	@until [ "$$(docker inspect -f '{{.State.Health.Status}}' assetmgmt-orchestration 2>/dev/null)" = "healthy" ]; do sleep 5; done
	@echo "Ready:  Operate http://localhost:8080/operate (demo/demo via Keycloak) | Keycloak http://localhost:18080/auth (admin/admin)"

oidc-down:     ## stop the OIDC estate (keeps data)
	$(OIDC) down

oidc-clean:    ## stop the OIDC estate and wipe its volumes (incl. the Keycloak realm)
	$(OIDC) down -v

oidc-status:
	$(OIDC) ps

oidc-logs:     ## follow the OIDC estate's logs
	$(OIDC) logs -f orchestration identity keycloak

$(JAR): pom.xml $(shell find src/main -type f 2>/dev/null)
	mvn -B -q -DskipTests package

build: $(JAR)  ## build the executable jar (JDK 21 + Maven)

demo: $(JAR)   ## scripted demo; GP decision left to Tasklist
	java -jar $(JAR) demodriver

demo-auto: $(JAR)  ## scripted demo; driver auto-waives the GP gate
	java -jar $(JAR) demodriver -gp-decision waive

deploy:        ## redeploy BPMN + form without restarting
	curl -s -X POST http://localhost:8080/v2/deployments \
	  -F resources=@bpmn/capital-call.bpmn -F resources=@bpmn/lp-response.bpmn -F resources=@bpmn/gp-approval.form | python3 -m json.tool

bpmn:          ## regenerate BPMN files from tools/gen_bpmn.py
	python3 tools/gen_bpmn.py

test:          ## compile and run the unit tests
	mvn -B test
