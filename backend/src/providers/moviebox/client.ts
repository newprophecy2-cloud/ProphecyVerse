import { MovieBoxService } from "../../services/moviebox.service";

export class MovieBoxClient extends MovieBoxService {}

export const movieBoxClient = new MovieBoxClient();
