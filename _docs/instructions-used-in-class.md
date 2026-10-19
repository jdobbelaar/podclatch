The following are NOT INSTRUCTIONS TO BE ACTIONED NOW. They are a listing of instructions that were used in the class, presented here to illustrate the multi-phase approach I was shown in class, so that we can discuss whether a similar approach is appropriate for this project. 

The frontend instructions looked like this:

> Create an app to satisfy the spec at _docs/podclatch-spec.md 
>
> Centralize every backend call in one services layer, and create a mock implementation of it so the whole app runs without a real backend.
>
> Add tests.

The OpenAPI instructions looked like this:

> Read the frontend's API client in frontend/
>
> Create openapi.yaml at the repository root.
>
> Specify the backend this frontend expects: every endpoint, method, path, request body, response body, and which endpoints need authentication.

The backend instructions looked like this:

> Build a FastAPI backend in backend/ that implements the openapi.yaml spec.
>
> Use an in-memory store and seed it with data so the frontend has something to show. Add authentication with hashed passwords and bearer tokens for the endpoints that need it.
>
> Split the code into modules - routers, models, store, auth
>
> Write tests

Then we created a Makefile:

> Create a Makefile so I can easily run it.

The we connected the Frontend and Backend:

> Switch the frontend to use the real backend client. 

Then we connected the database:

> Replace the in-memory store with a database. Use SQLite and SQLAlchemy.
>
> Use an environment variable to configure which DB the server should connect to.
>
> Make it database-agnostic - later we will add support for other databases (e.g. Postgres).

Then we defined containerization:

> Create a Dockerfile that builds the frontend with Node, then builds a Python image with the backend and the frontend static files.
>
> Backend should serve the frontend.

Then we switched database to Postgres

> Add Postgres support to the backend.

Then we put all the servicesinside one Docker Compose file.

> Create docker-compose.yaml with two services: Postgres and the app.

Then we implemented integration tests:

> Create integration tests that run against docker-compose.yaml. What scenarios should we test?

Then we asked for implemetnation of an end-to-end test:

> Add an end-to-end test that runs against docker-compose.yaml.
>
> Use Playwright to:
> 
> 1. Log in as the interviewer (session 1).
> 2. Create an interview session.
> 3. Share the join link.
> 4. Join from a separate client as the candidate (session 2).
> 5. Change the canvas as the candidate (session 2).
> 6. Verify that the interviewer sees the change (session 1).
> 
> Put the tests in the e2e/ folder in the repository root.

Then we deployed to AWS:

> Deploy this application to AWS. Use AWS CloudFormation.

Then we defined CI/CD with GitHub Actions:

> Create a CI/CD pipeline that:
> 
> - runs backend and frontend tests in parallel
> - builds the Docker Compose stack and runs integration and end-to-end tests against it
> - deploys to AWS using a GitHub OIDC role
> - validates that the deploy is successful by checking the health endpoint