# Independent primary-source facts

Observed 1 October 2026 through web open tools; no paid/vendor API calls.

- https://docs.spring.io/spring-boot/system-requirements.html: Boot 4.1.1; Java 17 through 26; Maven 3.6.3+. Website metadata does not establish installed/downloaded artifacts.
- https://spring.io/projects/spring-boot/: current displayed Boot 4.1.1.
- https://www.oracle.com/java/technologies/java-se-support-roadmap.html: Java 25 listed as LTS. Does not select a JDK distribution/license for builder.
- https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html: framework security-context persistence, session lifecycle/concurrent controls, logout context clearing and fixation protection. Application-specific credential version revocation still requires an explicit design.
- https://www.rfc-editor.org/rfc/rfc6238.txt: sections 3, 5.1 and 5.2: unique/generated secret, restricted secure key access and rejection of OTP reuse after successful validation. Atomic replay design is a reviewer inference necessary to preserve single use under concurrency. Provisioning is outside the RFC's scope.

Initial RFC HTML opens redirected to an info page and follow-up find calls returned internal errors; plain text open succeeded and relevant replay/security sections were inspected. No unsupported claim drawn from the failed find.
