FROM cpppo/cpppo@sha256:464639942d34f05a6779152ce793da2734af66b53bcad4763df4168e4b89928b
WORKDIR /app
COPY start.py /app/start.py
EXPOSE 44818/tcp
ENTRYPOINT ["python", "-u", "/app/start.py"]
