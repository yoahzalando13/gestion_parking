package com.example.parking.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Gestion des erreurs pour l'interface Thymeleaf : on renvoie un message
 * comprehensible a l'utilisateur, via un flash message ou une page d'erreur.
 */
@ControllerAdvice(basePackages = "com.example.parking.controller.web")
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler({BusinessException.class, InvalidOperationException.class, ConflictException.class,
            DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public RedirectView handleBusiness(Exception ex, HttpServletRequest request,
                                       RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", friendlyMessage(ex));
        String referer = request.getHeader("Referer");
        return new RedirectView(referer != null ? referer : "/dashboard");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(ResourceNotFoundException ex, org.springframework.ui.Model model) {
        model.addAttribute("errorTitle", "Ressource introuvable");
        model.addAttribute("errorMessage", ex.getMessage());
        model.addAttribute("statusCode", HttpStatus.NOT_FOUND.value());
        return "error/404";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleAccessDenied(AccessDeniedException ex, org.springframework.ui.Model model) {
        model.addAttribute("errorTitle", "Acces refuse");
        model.addAttribute("errorMessage", "Votre role ne permet pas d'acceder a cette page.");
        model.addAttribute("statusCode", HttpStatus.FORBIDDEN.value());
        return "error/403";
    }

    /** Parametre d'URL invalide (identifiant non numerique, valeur d'enum ou tri inconnus). */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            PropertyReferenceException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBadRequest(Exception ex, org.springframework.ui.Model model) {
        model.addAttribute("status", HttpStatus.BAD_REQUEST.value());
        model.addAttribute("error", "Requete invalide");
        model.addAttribute("message", "Un parametre de la requete est invalide ou inconnu.");
        return "error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, HttpServletRequest request, org.springframework.ui.Model model) {
        log.error("Erreur non geree sur {}", request.getRequestURI(), ex);
        model.addAttribute("errorTitle", "Erreur interne");
        model.addAttribute("errorMessage",
                "Une erreur inattendue est survenue. Contactez l'administrateur si le probleme persiste.");
        model.addAttribute("statusCode", HttpStatus.INTERNAL_SERVER_ERROR.value());
        return "error/500";
    }

    private String friendlyMessage(Exception ex) {
        if (ex instanceof DataIntegrityViolationException) {
            return "Operation impossible : cette valeur existe deja ou une reference est manquante.";
        }
        if (ex instanceof OptimisticLockingFailureException) {
            return "Cette place vient d'etre modifiee par un autre utilisateur. Merci de reessayer.";
        }
        return ex.getMessage();
    }
}
