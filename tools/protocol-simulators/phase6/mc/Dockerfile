FROM mcr.microsoft.com/dotnet/sdk@sha256:f7e28a0a3f6f9235929ec19274fd0ddf8534e9159f99fac602ac73304f1ce99f AS build
WORKDIR /src
COPY SimMc.csproj packages.lock.json ./
RUN dotnet restore SimMc.csproj --locked-mode
COPY Program.cs .
RUN dotnet publish SimMc.csproj -c Release -o /app/publish --no-restore --self-contained false

FROM mcr.microsoft.com/dotnet/runtime@sha256:60d11c2be381e7f90b8bd984472dd2432933858aba55087d5936980cf2071d5a AS runtime
WORKDIR /app
COPY --from=build /app/publish .
EXPOSE 15001/tcp
ENTRYPOINT ["dotnet", "SimMc.dll"]
