FROM python@sha256:e88e9763f943ec1834f992a4b51e0f24500486803e8bc534e5767af9ea65f6ce
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt
COPY server.py .
EXPOSE 47808/udp
ENTRYPOINT ["python", "server.py", "--name", "Phase6BACnet", "--instance", "101", "--address", "0.0.0.0/0:47808", "--debug", "bacpypes3.ipv4.IPv4DatagramServer"]
