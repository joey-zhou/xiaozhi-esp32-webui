cd /app
java -Dspring.datasource.url=jdbc:mysql://${DB_HOST}:3306/${DB_NAME} \
     -Dspring.datasource.username=${DB_USER} \
     -Dspring.datasource.password=${DB_PASSWORD} \
     -Dspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver \
     -jar app.jar &

cd /app/web
npm start