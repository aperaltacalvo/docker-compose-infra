# docker-compose-infra


To run this project you only need to execute a docker-compose up --build

This command is going to start:
 1) Jenkins in port 18080
 2) Nexux in port 18081
 3) Sonar in port 19000
 4) Ldap Apache Directory in port 10389
 
 
 All the content from volumes are saved in "data" directory, so useful to do backups and changes configuration.
