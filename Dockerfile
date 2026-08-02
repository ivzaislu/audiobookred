FROM mcr.microsoft.com/dotnet/sdk:9.0 AS build
WORKDIR /src
COPY src/AudioBookRed.Api/AudioBookRed.Api.csproj src/AudioBookRed.Api/packages.lock.json src/AudioBookRed.Api/
RUN dotnet restore src/AudioBookRed.Api/AudioBookRed.Api.csproj --locked-mode
COPY . .
RUN dotnet publish src/AudioBookRed.Api/AudioBookRed.Api.csproj -c Release -o /app/publish --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:9.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/publish .
ENV ASPNETCORE_URLS=http://+:9117
EXPOSE 9117
USER app
ENTRYPOINT ["dotnet", "AudioBookRed.Api.dll"]
